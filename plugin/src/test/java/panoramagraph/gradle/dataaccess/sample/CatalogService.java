package panoramagraph.gradle.dataaccess.sample;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

public class CatalogService {
    private final ProductRepository products;
    private final LineItemRepository lineItems;
    private final MongoTemplate mongoTemplate;

    public CatalogService(ProductRepository products, LineItemRepository lineItems, MongoTemplate mongoTemplate) {
        this.products = products;
        this.lineItems = lineItems;
        this.mongoTemplate = mongoTemplate;
    }

    public Product save(Product product) {
        return products.save(product);
    }

    public Optional<Product> get(String id) {
        return products.findById(id);
    }

    public List<Product> page() {
        return products.findSlice(10);
    }

    public List<Product> byName(String name) {
        return products.findByName(name);
    }

    public void view(String id) {
        products.findAndIncrementViewsById(id);
    }

    public void archive() {
        products.archiveOld();
    }

    public void removeItem(Long id) {
        lineItems.deleteById(id);
    }

    public long nextCode() {
        Sequence s = mongoTemplate.findAndModify(
                new Query(), new Update().inc("value", 1L), FindAndModifyOptions.options().upsert(true), Sequence.class);
        return s.value;
    }

    public void insert() {
        Product product = new Product();
        mongoTemplate.insert(product);
    }

    public long audits() {
        return mongoTemplate.count(new Query(), "audit_log");
    }

    public List<Product> anywhere(String collection) {
        return mongoTemplate.find(new Query(), Product.class, collection);
    }

    public <T> List<T> generic(Class<T> type) {
        return mongoTemplate.findAll(type);
    }
}
