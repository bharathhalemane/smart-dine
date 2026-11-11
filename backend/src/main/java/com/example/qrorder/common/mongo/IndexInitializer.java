package com.example.qrorder.common.mongo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver.IndexDefinitionHolder;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.stereotype.Component;

/**
 * Creates every index declared on the @Document classes, explicitly, before the web server starts.
 * Never drops anything. A conflicting existing index stops startup with a message naming it.
 * Enabled by app.indexes.ensure-on-startup=true (the prod profile).
 */
@Component
@ConditionalOnProperty(prefix = "app.indexes", name = "ensure-on-startup", havingValue = "true")
public class IndexInitializer implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(IndexInitializer.class);

    public record Report(List<String> created, int declared) {
    }

    private final MongoTemplate template;

    public IndexInitializer(MongoTemplate template) {
        this.template = template;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Report report = ensureAll();
        log.info("Indexes ensured: {} declared, {} created {}", report.declared(), report.created().size(),
                report.created());
    }

    public Report ensureAll() {
        MappingContext<? extends MongoPersistentEntity<?>, ?> context = template.getConverter().getMappingContext();
        MongoPersistentEntityIndexResolver resolver = new MongoPersistentEntityIndexResolver((MongoMappingContext) context);
        List<String> created = new ArrayList<>();
        int declared = 0;
        for (MongoPersistentEntity<?> entity : context.getPersistentEntities()) {
            if (!entity.isAnnotationPresent(Document.class)) {
                continue;
            }
            for (IndexDefinition definition : resolver.resolveIndexFor(entity.getType())) {
                String collection = definition instanceof IndexDefinitionHolder holder
                        ? holder.getCollection() : entity.getCollection();
                IndexOperations operations = template.indexOps(collection);
                Set<String> before = namesOf(operations);
                try {
                    operations.ensureIndex(definition);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("Cannot create index " + definition.getIndexOptions().get("name")
                            + " " + definition.getIndexKeys().toJson() + " on collection '" + collection
                            + "'. An index with that name or those keys already exists with different options, or existing"
                            + " data violates a unique index. See docs/operations.md, 'Changing an index'.", e);
                }
                declared++;
                for (String name : namesOf(operations)) {
                    if (!before.contains(name) && !name.equals("_id_")) {
                        created.add(collection + "." + name);
                    }
                }
            }
        }
        return new Report(created, declared);
    }

    private static Set<String> namesOf(IndexOperations operations) {
        try {
            return operations.getIndexInfo().stream().map(IndexInfo::getName).collect(Collectors.toCollection(HashSet::new));
        } catch (RuntimeException e) {
            return new HashSet<>(); // the collection does not exist yet
        }
    }
}