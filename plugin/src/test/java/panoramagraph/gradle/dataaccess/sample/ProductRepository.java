package panoramagraph.gradle.dataaccess.sample;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Update;

public interface ProductRepository extends MongoRepository<Product, String>, ProductRepositoryCustom {

    List<Product> findByName(String name);

    @Update("{ '$inc': { 'views': 1 } }")
    void findAndIncrementViewsById(String id);

    void archiveOld();
}
