package ma.youscout.chat;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.mongodb.MongoException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Historique des conversations à deux. Un message est enregistré avant d'être accusé ; sa séquence est
 * attribuée dans la même transaction que l'insertion, sans trou ni doublon, et l'identifiant client
 * choisi par l'expéditeur rend le renvoi après reconnexion idempotent.
 */
@Component
class ChatStore {
    static final String CONVERSATIONS = "conversations";
    static final String MESSAGES = "messages";
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    // Les écritures simultanées d'une même conversation se disputent son compteur : MongoDB annule les perdantes.
    private static final int MAX_ATTEMPTS = 10;

    record Message(String id, String conversationId, long seq, String senderSubject, String recipientSubject,
                   String clientMessageId, String body, Instant acceptedAt) {}
    record Accepted(Message message, boolean created) {}
    record History(String conversationId, String peerSubject, long lastSeq, long peerDeliveredSeq, List<Message> messages) {}
    record LastMessage(String senderSubject, String body, long seq, Instant acceptedAt) {}
    record Conversation(String conversationId, String peerSubject, long lastSeq, long myDeliveredSeq,
                        long peerDeliveredSeq, LastMessage lastMessage) {}
    record Delivered(String conversationId, long deliveredSeq, boolean changed) {}

    private final MongoTemplate mongo;
    private final TransactionTemplate transactions;
    private final ChatReferences references;

    ChatStore(MongoTemplate mongo, TransactionTemplate chatTransactions, ChatReferences references) {
        this.mongo = mongo;
        this.transactions = chatTransactions;
        this.references = references;
    }

    Accepted send(String sender, String recipient, Object clientMessageId, Object rawBody, String token) {
        validateSubject(recipient);
        if (sender.equals(recipient)) throw bad("Tu ne peux pas t'envoyer un message.");
        if (!(clientMessageId instanceof String clientId) || !CLIENT_ID.matcher(clientId).matches())
            throw bad("L'identifiant client du message est invalide.");
        String body = rawBody instanceof String text ? text.strip() : "";
        if (body.isEmpty() || body.length() > 2000 || body.indexOf('\0') >= 0) throw bad("Le message doit contenir de 1 à 2000 caractères.");

        // Un renvoi reçoit l'accusé d'origine, même si un blocage est survenu depuis l'acceptation.
        Document existing = byClientId(sender, clientId);
        if (existing != null) return duplicate(existing, recipient, body);
        references.requireProfile(recipient, token);
        if (references.blockedEitherWay(token).contains(recipient))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Un blocage empêche ce message.");

        String conversation = conversationId(sender, recipient);
        createConversation(conversation, sender, recipient);
        for (int attempt = 1; ; attempt++) {
            try {
                return new Accepted(transactions.execute(status -> append(conversation, sender, recipient, clientId, body)), true);
            } catch (DuplicateKeyException concurrentResend) {
                existing = byClientId(sender, clientId);
                if (existing != null) return duplicate(existing, recipient, body);
                if (attempt >= MAX_ATTEMPTS) throw concurrentResend;
            } catch (RuntimeException failure) {
                if (attempt >= MAX_ATTEMPTS || !transientTransaction(failure)) throw failure;
            }
            pause(attempt);
        }
    }

    History history(String me, String peer, Long afterSeq, Long beforeSeq, int limit) {
        validateSubject(peer);
        if (limit < 1 || limit > 200) throw bad("La taille de page doit être comprise entre 1 et 200.");
        if (afterSeq != null && beforeSeq != null) throw bad("Utiliser afterSeq ou beforeSeq, pas les deux.");
        String id = conversationId(me, peer);
        Document conversation = mongo.findById(id, Document.class, CONVERSATIONS);
        Criteria criteria = where("conversationId").is(id);
        boolean forward = afterSeq != null;
        if (afterSeq != null) criteria = criteria.and("seq").gt(afterSeq);
        if (beforeSeq != null) criteria = criteria.and("seq").lt(beforeSeq);
        var page = new Query(criteria).with(Sort.by(forward ? Sort.Direction.ASC : Sort.Direction.DESC, "seq")).limit(limit);
        List<Message> messages = mongo.find(page, Document.class, MESSAGES).stream().map(ChatStore::message).toList();
        return new History(id, peer, conversation == null ? 0 : number(conversation, "lastSeq"),
            conversation == null ? 0 : deliveredSeq(conversation, peer), forward ? messages : messages.reversed());
    }

    List<Conversation> conversations(String me, int limit) {
        if (limit < 1 || limit > 100) throw bad("La taille de page doit être comprise entre 1 et 100.");
        var page = query(where("members.subject").is(me).and("lastMessage").exists(true))
            .with(Sort.by(Sort.Direction.DESC, "lastMessageAt")).limit(limit);
        return mongo.find(page, Document.class, CONVERSATIONS).stream().map(c -> {
            String peer = c.getList("members", Document.class).stream().map(m -> m.getString("subject"))
                .filter(subject -> !subject.equals(me)).findFirst().orElseThrow();
            Document last = c.get("lastMessage", Document.class);
            return new Conversation(c.getString("_id"), peer, number(c, "lastSeq"), deliveredSeq(c, me), deliveredSeq(c, peer),
                new LastMessage(last.getString("senderSubject"), last.getString("body"), number(last, "seq"), instant(last, "acceptedAt")));
        }).toList();
    }

    /** Le destinataire confirme avoir reçu la conversation jusqu'à {@code seq} ; la valeur ne fait que croître. */
    Delivered markDelivered(String me, String peer, Object rawSeq) {
        validateSubject(peer);
        if (!(rawSeq instanceof Number number) || number.longValue() < 1 || number.doubleValue() != number.longValue())
            throw bad("La séquence doit être un entier positif.");
        long seq = number.longValue();
        String id = conversationId(me, peer);
        var update = mongo.updateFirst(query(where("_id").is(id).and("lastSeq").gte(seq)
                .and("members").elemMatch(where("subject").is(me))),
            new Update().max("members.$.deliveredSeq", seq), CONVERSATIONS);
        if (update.getMatchedCount() == 0) {
            if (mongo.findById(id, Document.class, CONVERSATIONS) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation introuvable.");
            throw bad("Cette séquence n'existe pas encore dans la conversation.");
        }
        return new Delivered(id, seq, update.getModifiedCount() > 0);
    }

    static String conversationId(String a, String b) {
        String pair = a.compareTo(b) < 0 ? a + "\0" + b : b + "\0" + a;
        return UUID.nameUUIDFromBytes(pair.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static void validateSubject(String s) {
        if (s == null || s.isBlank() || s.length() > 255 || s.indexOf('\0') >= 0) throw bad("Identifiant utilisateur invalide.");
    }

    private void createConversation(String id, String sender, String recipient) {
        String first = sender.compareTo(recipient) < 0 ? sender : recipient;
        String second = first.equals(sender) ? recipient : sender;
        mongo.upsert(query(where("_id").is(id)), new Update()
            .setOnInsert("members", List.of(new Document("subject", first).append("deliveredSeq", 0L),
                new Document("subject", second).append("deliveredSeq", 0L)))
            .setOnInsert("lastSeq", 0L), CONVERSATIONS);
    }

    private Message append(String conversation, String sender, String recipient, String clientId, String body) {
        Date now = Date.from(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        Document counter = mongo.findAndModify(query(where("_id").is(conversation)), new Update().inc("lastSeq", 1L),
            FindAndModifyOptions.options().returnNew(true), Document.class, CONVERSATIONS);
        long seq = number(counter, "lastSeq");
        var message = new Document("_id", UUID.randomUUID().toString()).append("conversationId", conversation).append("seq", seq)
            .append("senderSubject", sender).append("recipientSubject", recipient).append("clientMessageId", clientId)
            .append("body", body).append("acceptedAt", now);
        mongo.insert(message, MESSAGES);
        mongo.updateFirst(query(where("_id").is(conversation)), new Update().set("lastMessageAt", now)
            .set("lastMessage", new Document("senderSubject", sender).append("body", body).append("seq", seq).append("acceptedAt", now)),
            CONVERSATIONS);
        return message(message);
    }

    private Document byClientId(String sender, String clientId) {
        return mongo.findOne(query(where("senderSubject").is(sender).and("clientMessageId").is(clientId)), Document.class, MESSAGES);
    }

    private static Accepted duplicate(Document existing, String recipient, String body) {
        if (!existing.getString("recipientSubject").equals(recipient) || !existing.getString("body").equals(body))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cet identifiant client est déjà utilisé pour un autre message.");
        return new Accepted(message(existing), false);
    }

    private static Message message(Document d) {
        return new Message(d.getString("_id"), d.getString("conversationId"), number(d, "seq"), d.getString("senderSubject"),
            d.getString("recipientSubject"), d.getString("clientMessageId"), d.getString("body"), instant(d, "acceptedAt"));
    }

    private static long deliveredSeq(Document conversation, String subject) {
        return conversation.getList("members", Document.class).stream().filter(m -> subject.equals(m.getString("subject")))
            .findFirst().map(m -> number(m, "deliveredSeq")).orElse(0L);
    }

    private static long number(Document d, String key) { return ((Number) d.get(key)).longValue(); }
    private static Instant instant(Document d, String key) { return d.getDate(key).toInstant(); }

    private static boolean transientTransaction(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof MongoException mongo && mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) return true;
        return false;
    }

    private static void pause(int attempt) {
        try {
            // Attente aléatoire et croissante pour que les transactions concurrentes ne se heurtent pas de nouveau.
            Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(5, 10L + 20L * attempt));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Messagerie temporairement indisponible.");
        }
    }

    static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
