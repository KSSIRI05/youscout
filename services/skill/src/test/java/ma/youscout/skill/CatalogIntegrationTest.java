package ma.youscout.skill;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests HTTP réels ; les identités sont signées par la fixture locale. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CatalogIntegrationTest {
    static final OidcFixture oidc=new OidcFixture();
    // Base éphémère indépendante des volumes Compose et des données du développeur.
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17.11-alpine");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    WebTestClient client;
    String user,admin;
    static final UUID SEED=UUID.fromString("00000000-0000-4000-8000-000000000001");
    // Remplace les adresses de production par celles des ressources temporaires du test.
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",oidc::base);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",()->oidc.base()+"/jwks");
        r.add("spring.datasource.url",postgres::getJdbcUrl);
        r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
    }
    // Réinitialise le contexte du test pour rendre les scénarios indépendants.
    @BeforeEach void setup() {
        client=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build();
        user=oidc.profileToken("alice","Alice",null);admin=oidc.roleToken("admin");
        jdbc.update("DELETE FROM hashtag_follows");jdbc.update("DELETE FROM hashtags");jdbc.update("DELETE FROM skill_audit");jdbc.update("DELETE FROM skills");
        jdbc.update("INSERT INTO skills(id,name,name_key) VALUES (?,'Dribble','dribble')",SEED);
    }
    @AfterAll static void close() { oidc.server.stop(0); }
    WebTestClient.ResponseSpec call(String method,String path,String token,String body) {
        var request=client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path).headers(h->{if(token!=null)h.setBearerAuth(token);});
        if(body!=null)return request.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
        return request.exchange();
    }
    UUID tag(String name) {
        call("POST","/v1/hashtags",user,"{\"name\":\""+name+"\"}").expectStatus().isCreated();
        return jdbc.queryForObject("SELECT id FROM hashtags",UUID.class);
    }
    // Vérifie la disponibilité de l’application et l’historique de migration.
    @Test void migrationAndHealth() {
        call("GET","/actuator/health",null,null).expectStatus().isOk();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='1' AND success",Integer.class)).isEqualTo(1);
    }
    // Teste la recherche normalisée et le décalage des résultats du catalogue utilisateur.
    @Test void readersSeeActiveSkillsAndPagination() {
        call("GET","/v1/skills?q=DRIB&limit=1&offset=0",user,null).expectStatus().isOk().expectBody()
            .jsonPath("$.items.length()").isEqualTo(1).jsonPath("$.items[0].name").isEqualTo("Dribble");
        call("GET","/v1/skills?offset=1",user,null).expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(0);
    }
    // La désactivation masque la compétence aux joueurs mais laisse sa réactivation possible.
    @Test void adminCreatesRenamesDeactivatesAndReactivates() {
        call("POST","/v1/skills/admin",admin,"{\"name\":\"  Vision\\tdu jeu  \",\"description\":\"Lecture du jeu\"}")
            .expectStatus().isCreated().expectHeader().exists("Location").expectBody().jsonPath("$.name").isEqualTo("Vision du jeu");
        UUID id=jdbc.queryForObject("SELECT id FROM skills WHERE name_key='vision du jeu'",UUID.class);
        call("PATCH","/v1/skills/admin/"+id,admin,"{\"name\":\"Vision\",\"active\":false}").expectStatus().isOk()
            .expectBody().jsonPath("$.description").isEqualTo("Lecture du jeu").jsonPath("$.active").isEqualTo(false);
        call("GET","/v1/skills/"+id,user,null).expectStatus().isNotFound();
        call("GET","/v1/skills?q=vision",user,null).expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(0);
        call("GET","/v1/skills/admin/"+id,admin,null).expectStatus().isOk();
        call("GET","/v1/skills/admin?q=vision",admin,null).expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(1);
        call("PATCH","/v1/skills/admin/"+id,admin,"{\"active\":true,\"description\":\"\"}").expectStatus().isOk();
        call("GET","/v1/skills/"+id,user,null).expectStatus().isOk().expectBody().jsonPath("$.description").isEqualTo("");
    }
    // Un doublon canonique renvoie 409 et annule aussi les autres champs du PATCH.
    @Test void duplicateCanonicalNamesAreConflictsAndRollback() {
        call("POST","/v1/skills/admin",admin,"{\"name\":\"ＤＲＩＢＢＬＥ\"}").expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.status").isEqualTo(409);
        call("POST","/v1/skills/admin",admin,"{\"name\":\"Passe\"}").expectStatus().isCreated();
        UUID id=jdbc.queryForObject("SELECT id FROM skills WHERE name_key='passe'",UUID.class);
        call("PATCH","/v1/skills/admin/"+id,admin,"{\"name\":\"dribble\",\"active\":false}").expectStatus().isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT active FROM skills WHERE id=?",Boolean.class,id)).isTrue();
    }
    // Un joueur ne peut pas contourner les droits admin avec un en-tête de rôle.
    @Test void usersCannotWriteSkillsEvenWithSpoofedRoles() {
        call("POST","/v1/skills/admin",user,"{\"name\":\"Interdit\"}").expectStatus().isForbidden();
        client.patch().uri("/v1/skills/admin/"+SEED).header("X-User-Roles","admin").headers(h->h.setBearerAuth(user))
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"active\":false}").exchange().expectStatus().isForbidden();
        call("GET","/v1/skills/admin",user,null).expectStatus().isForbidden();
        assertThat(jdbc.queryForObject("SELECT active FROM skills WHERE id=?",Boolean.class,SEED)).isTrue();
    }
    // Vérifie séparément les refus liés au rôle et ceux liés à l’authentification.
    @Test void rolelessAnonymousExpiredAndWrongAudienceAreRejected() {
        call("GET","/v1/skills",null,null).expectStatus().isUnauthorized();
        call("GET","/v1/hashtags",oidc.roleToken(),null).expectStatus().isForbidden();
        call("GET","/v1/skills",oidc.token(oidc.base(),"wrong",Instant.now().plusSeconds(300),false),null).expectStatus().isUnauthorized();
        call("GET","/v1/skills",oidc.token(oidc.base(),"youscout-api",Instant.now().minusSeconds(120),false),null).expectStatus().isUnauthorized();
    }
    // Les erreurs de JSON, de type ou de champ ne doivent laisser aucune compétence créée.
    @Test void invalidSkillInputsAreProblemsWithoutWrites() {
        for(String body:new String[]{"{}","null","[]","{","{\"name\":null}","{\"name\":12}","{\"name\":\" \"}",
            "{\"name\":\"Test\",\"id\":\"forged\"}","{\"name\":\""+"x".repeat(81)+"\"}","{\"name\":\"Test\",\"description\":null}"}) {
            call("POST","/v1/skills/admin",admin,body).expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
        for(String body:new String[]{"{}","{\"active\":null}","{\"active\":\"false\"}","{\"description\":\""+"x".repeat(501)+"\"}"})
            call("PATCH","/v1/skills/admin/"+SEED,admin,body).expectStatus().isBadRequest();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skills",Integer.class)).isEqualTo(1);
    }
    // Distingue une ressource inconnue des identifiants et paramètres invalides.
    @Test void missingResourcesInvalidIdsAndPageBounds() {
        call("GET","/v1/skills/"+UUID.randomUUID(),user,null).expectStatus().isNotFound();
        call("PATCH","/v1/skills/admin/"+UUID.randomUUID(),admin,"{\"active\":false}").expectStatus().isNotFound();
        call("GET","/v1/hashtags/not-a-uuid",user,null).expectStatus().isBadRequest();
        for(String query:new String[]{"limit=0","limit=101","offset=-1","offset=10001","limit=invalid","q="+"a".repeat(81)})
            call("GET","/v1/skills?"+query,user,null).expectStatus().isBadRequest();
    }
    // Des écritures compatibles et des casses différentes renvoient le même hashtag.
    @Test void hashtagsNormalizeAndReuseCanonicalIds() {
        UUID id=tag("#ＦｏｏｔＢａｌｌ");
        call("POST","/v1/hashtags",user,"{\"name\":\"football\"}").expectStatus().isOk().expectBody().jsonPath("$.id").isEqualTo(id.toString());
        client.get().uri("/v1/hashtags?q={q}","#FOOT").headers(h->h.setBearerAuth(user)).exchange().expectStatus().isOk().expectBody().jsonPath("$.items[0].name").isEqualTo("football");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hashtags",Integer.class)).isEqualTo(1);
    }
    // Refuse les noms invalides et les champs internes ajoutés au corps JSON.
    @Test void hashtagsRejectInvalidNamesAndMassAssignment() {
        for(String body:new String[]{"{}","{\"name\":\"#\"}","{\"name\":\"two words\"}","{\"name\":\"bad-tag\"}",
            "{\"name\":null}","{\"name\":123}","{\"name\":\"\\u0000\"}","{\"name\":\""+"a".repeat(51)+"\"}","{\"name\":\"valid\",\"subject\":\"forged\"}"})
            call("POST","/v1/hashtags",user,body).expectStatus().isBadRequest();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hashtags",Integer.class)).isZero();
    }
    // Les abonnements répétés restent uniques et chaque compte ne gère que les siens.
    @Test void followingIsIdempotentAndOwnedByJwtSubject() {
        UUID id=tag("football");String bob=oidc.profileToken("bob","Bob",null);
        call("PUT","/v1/hashtags/"+id+"/follow",user,null).expectStatus().isNoContent();
        call("PUT","/v1/hashtags/"+id+"/follow",user,null).expectStatus().isNoContent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hashtag_follows",Integer.class)).isEqualTo(1);
        client.get().uri("/v1/hashtags/following?subject=alice").header("X-User-Id","alice").headers(h->h.setBearerAuth(bob))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(0);
        call("DELETE","/v1/hashtags/"+id+"/follow",bob,null).expectStatus().isNoContent();
        call("GET","/v1/hashtags/following",user,null).expectStatus().isOk().expectBody().jsonPath("$.items[0].id").isEqualTo(id.toString());
        call("DELETE","/v1/hashtags/"+id+"/follow",user,null).expectStatus().isNoContent();
        call("DELETE","/v1/hashtags/"+id+"/follow",user,null).expectStatus().isNoContent();
        call("GET","/v1/hashtags/following",user,null).expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(0);
    }
    // Un abonnement exige une identité valide et un hashtag existant.
    @Test void followRejectsInvalidSubjectsAndUnknownTags() {
        UUID id=tag("football");String noSubject=oidc.profileToken(null,"Unknown",null);
        call("PUT","/v1/hashtags/"+id+"/follow",noSubject,null).expectStatus().isUnauthorized();
        call("GET","/v1/hashtags/following",noSubject,null).expectStatus().isUnauthorized();
        call("PUT","/v1/hashtags/"+UUID.randomUUID()+"/follow",user,null).expectStatus().isNotFound();
        call("DELETE","/v1/hashtags/"+UUID.randomUUID()+"/follow",user,null).expectStatus().isNotFound();
    }
    // Exerce les contraintes PostgreSQL avec plusieurs créations et abonnements simultanés.
    @Test void concurrentHashtagCreationAndFollowingStayUnique() throws Exception {
        try(var pool=Executors.newFixedThreadPool(6)) {
            var work=new ArrayList<Callable<Integer>>();
            for(int i=0;i<10;i++)work.add(()->call("POST","/v1/hashtags",user,"{\"name\":\"#Unique\"}").expectBody().returnResult().getStatus().value());
            for(var result:pool.invokeAll(work))assertThat(result.get(30,TimeUnit.SECONDS)).isIn(200,201);
            UUID id=jdbc.queryForObject("SELECT id FROM hashtags",UUID.class);
            work.clear();for(int i=0;i<10;i++)work.add(()->call("PUT","/v1/hashtags/"+id+"/follow",user,null).expectBody().returnResult().getStatus().value());
            for(var result:pool.invokeAll(work))assertThat(result.get(30,TimeUnit.SECONDS)).isEqualTo(204);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hashtags",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hashtag_follows",Integer.class)).isEqualTo(1);
    }
    // Deux créations concurrentes du même nom doivent produire un succès et un conflit.
    @Test void concurrentSkillCreationPreservesUniqueness() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->call("POST","/v1/skills/admin",admin,"{\"name\":\"Same\"}").expectBody().returnResult().getStatus().value());
            var b=pool.submit(()->call("POST","/v1/skills/admin",admin,"{\"name\":\"ＳＡＭＥ\"}").expectBody().returnResult().getStatus().value());
            assertThat(List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        }
    }
    // Vérifie Unicode et la recherche littérale, sans interprétation des jokers SQL.
    @Test void unicodeAndLiteralSearchAvoidWildcardExpansion() {
        call("POST","/v1/skills/admin",admin,"{\"name\":\""+"😀".repeat(80)+"\"}").expectStatus().isCreated();
        client.get().uri("/v1/skills?q={q}","%").headers(h->h.setBearerAuth(user)).exchange().expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(0);
        tag("كرة_القدم");
        call("GET","/v1/hashtags",user,null).expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(1);
    }

    // Le journal conserve le véritable acteur ; un admin sans sujet ne peut pas écrire.
    @Test void adminChangesAreAuditedWithVerifiedSubject() {
        client.patch().uri("/v1/skills/admin/"+SEED).headers(h->h.setBearerAuth(admin)).header("X-User-Id","forged")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"active\":false}").exchange().expectStatus().isOk();
        assertThat(jdbc.queryForObject("SELECT actor_subject FROM skill_audit WHERE skill_id=?",String.class,SEED)).isEqualTo("role-test");
        assertThat(jdbc.queryForObject("SELECT action FROM skill_audit WHERE skill_id=?",String.class,SEED)).isEqualTo("UPDATE");
        String noSubject=oidc.rolesToken(Map.of("roles",List.of("admin")),null,null,Instant.now().plusSeconds(300),null);
        call("PATCH","/v1/skills/admin/"+SEED,noSubject,"{\"active\":true}").expectStatus().isUnauthorized();
        assertThat(jdbc.queryForObject("SELECT active FROM skills WHERE id=?",Boolean.class,SEED)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_audit",Integer.class)).isEqualTo(1);
    }
}
