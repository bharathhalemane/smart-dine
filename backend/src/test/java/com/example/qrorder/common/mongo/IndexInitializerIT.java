package com.example.qrorder.common.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.qrorder.support.AbstractIntegrationTest;
import com.mongodb.client.MongoClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;
import java.util.HashSet;
import java.util.Set;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

/** Runs the initializer against a brand-new database, so indexes created by other tests cannot hide a gap. */
class IndexInitializerIT extends AbstractIntegrationTest {

    @Autowired MongoTemplate mongo;
    @Autowired MongoClient client;
    @Autowired MongoCustomConversions conversions;

    private MongoTemplate freshDatabase() {
        String name = "idx_" + UUID.randomUUID().toString().substring(0, 8);

        // Same entities as the application, but with automatic index creation OFF, so only the initializer creates indexes.
        Set<Class<?>> entityTypes = new HashSet<>();
        mongo.getConverter().getMappingContext().getPersistentEntities().forEach(e -> entityTypes.add(e.getType()));
        MongoMappingContext context = new MongoMappingContext();
        context.setAutoIndexCreation(false);
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.setInitialEntitySet(entityTypes);
        context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();

        return new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, name), converter);
    }

    @Test
    void createsTheDeclaredIndexesOnAnEmptyDatabaseAndIsIdempotent() {
        MongoTemplate fresh = freshDatabase();
        try {
            IndexInitializer.Report first = new IndexInitializer(fresh).ensureAll();
            assertThat(first.declared()).isGreaterThan(0);
            assertThat(first.created()).isNotEmpty();

            assertThat(fresh.indexOps("diningSessions").getIndexInfo()).anySatisfy(info -> {
                assertThat(info.getName()).isEqualTo("one_active_per_table");
                assertThat(info.isUnique()).isTrue();
            });

            assertThat(new IndexInitializer(fresh).ensureAll().created()).isEmpty();
        } finally {
            fresh.getDb().drop();
        }
    }

    @Test
    void aConflictingExistingIndexStopsTheInitializerWithAClearMessage() {
        MongoTemplate fresh = freshDatabase();
        try {
            fresh.indexOps("diningSessions").ensureIndex(new Index().on("somethingElse", Sort.Direction.ASC)
                    .named("one_active_per_table"));

            assertThatThrownBy(() -> new IndexInitializer(fresh).ensureAll())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("one_active_per_table");
        } finally {
            fresh.getDb().drop();
        }
    }
}