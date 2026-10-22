package panoramagraph.gradle.dataaccess.sample;

import org.springframework.data.mongodb.core.mapping.Document;

@Document("products")
public class Product {
    String id;
    String name;
}
