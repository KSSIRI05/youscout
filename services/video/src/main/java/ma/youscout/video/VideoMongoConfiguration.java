package ma.youscout.video;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Mongo is the application transaction store; PostgreSQL is retained only for the explicit snapshot importer. */
@Configuration(proxyBeanMethods = false)
class VideoMongoConfiguration {
    @Bean("mongoTransactionManager")
    @Primary
    PlatformTransactionManager mongoTransactionManager(MongoDatabaseFactory factory) {
        return new MongoTransactionManager(factory);
    }

    @Bean("postgresTransactionManager")
    PlatformTransactionManager postgresTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
