package ma.youscout.chat;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactions MongoDB (replica set requis) et index garantissant séquence et déduplication. */
@Configuration(proxyBeanMethods = false)
class ChatMongoConfiguration {
    @Bean TransactionTemplate chatTransactions(MongoDatabaseFactory factory) {
        var transactions = new TransactionTemplate(new MongoTransactionManager(factory));
        transactions.setTimeout(10);
        return transactions;
    }

    // Les collections existent avant la première transaction ; les index rendent les doublons impossibles même en concurrence.
    @Bean ApplicationRunner chatIndexes(MongoTemplate mongo) {
        return args -> {
            var messages = mongo.indexOps(ChatStore.MESSAGES);
            messages.createIndex(new Index().on("conversationId", Sort.Direction.ASC).on("seq", Sort.Direction.ASC).unique());
            messages.createIndex(new Index().on("senderSubject", Sort.Direction.ASC).on("clientMessageId", Sort.Direction.ASC).unique());
            mongo.indexOps(ChatStore.CONVERSATIONS)
                .createIndex(new Index().on("members.subject", Sort.Direction.ASC).on("lastMessageAt", Sort.Direction.DESC));
        };
    }
}
