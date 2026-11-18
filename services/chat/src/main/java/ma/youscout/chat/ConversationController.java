package ma.youscout.chat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Historique, reprise après reconnexion et envoi de secours sans WebSocket. Les messages envoyés ici
 * sont relayés aux connexions WebSocket comme ceux envoyés par la connexion temps réel.
 */
@RestController
@RequestMapping("/v1/conversations")
class ConversationController {
    private final ChatStore store;
    private final ChatRelay relay;

    ConversationController(ChatStore store, ChatRelay relay) {
        this.store = store;
        this.relay = relay;
    }

    @GetMapping
    ResponseEntity<List<ChatStore.Conversation>> conversations(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "50") int limit) {
        return noStore(HttpStatus.OK, store.conversations(subject(jwt), limit));
    }

    /** Sans curseur : page la plus récente ; {@code afterSeq} reprend après reconnexion, {@code beforeSeq} remonte l'historique. */
    @GetMapping("/{peer}/messages")
    ResponseEntity<ChatStore.History> messages(@AuthenticationPrincipal Jwt jwt, @PathVariable String peer,
                                               @RequestParam(required = false) Long afterSeq, @RequestParam(required = false) Long beforeSeq,
                                               @RequestParam(defaultValue = "50") int limit) {
        return noStore(HttpStatus.OK, store.history(subject(jwt), peer, afterSeq, beforeSeq, limit));
    }

    /** 201 pour un nouveau message, 200 avec le message d'origine pour un renvoi du même {@code clientMessageId}. */
    @PostMapping("/{peer}/messages")
    ResponseEntity<ChatStore.Message> send(@AuthenticationPrincipal Jwt jwt, @PathVariable String peer, @RequestBody Map<String, Object> body) {
        if (!body.keySet().stream().allMatch(Set.of("clientMessageId", "body")::contains)) throw ChatStore.bad("Champs inconnus dans le message.");
        String me = subject(jwt);
        var accepted = store.send(me, peer, body.get("clientMessageId"), body.get("body"), jwt.getTokenValue());
        if (accepted.created()) relay.publish(Set.of(me, peer), null, Map.of("type", "message", "message", accepted.message()));
        return noStore(accepted.created() ? HttpStatus.CREATED : HttpStatus.OK, accepted.message());
    }

    @PostMapping("/{peer}/delivered")
    ResponseEntity<ChatStore.Delivered> delivered(@AuthenticationPrincipal Jwt jwt, @PathVariable String peer, @RequestBody Map<String, Object> body) {
        String me = subject(jwt);
        var delivered = store.markDelivered(me, peer, body.get("seq"));
        if (delivered.changed()) relay.publish(Set.of(peer), null,
            Map.of("type", "delivered", "conversationId", delivered.conversationId(), "peerSubject", me, "seq", delivered.deliveredSeq()));
        return noStore(HttpStatus.OK, delivered);
    }

    private static String subject(Jwt jwt) { String s = jwt.getSubject(); ChatStore.validateSubject(s); return s; }
    private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) { return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body); }
}
