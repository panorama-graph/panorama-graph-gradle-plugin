package panoramagraph.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdentityTest {

    @Test
    void oRemotoNaoGuardaCredencial() {
        assertThat(Identity.withoutCredentials("https://ci-token:segredo@git.example.com/example/x.git"))
                .isEqualTo("https://git.example.com/example/x.git");
        assertThat(Identity.withoutCredentials("ssh://git@git.example.com:2224/example/x.git"))
                .isEqualTo("ssh://git@git.example.com:2224/example/x.git");
    }
}
