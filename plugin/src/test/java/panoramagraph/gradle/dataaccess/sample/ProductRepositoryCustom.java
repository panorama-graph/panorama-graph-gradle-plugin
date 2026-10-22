package panoramagraph.gradle.dataaccess.sample;

import java.util.List;

public interface ProductRepositoryCustom {
    List<Product> findSlice(int limit);
}
