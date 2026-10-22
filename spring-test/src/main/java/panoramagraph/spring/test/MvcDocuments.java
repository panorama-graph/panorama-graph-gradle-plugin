package panoramagraph.spring.test;

import java.nio.charset.StandardCharsets;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Os documentos que a aplicação Spring MVC serve (OpenAPI, AsyncAPI), lidos pelo MockMvc. Classe à parte para que as
 * classes do Spring MVC só sejam carregadas quando existem.
 */
final class MvcDocuments {

    private MvcDocuments() {}

    static boolean isServletWeb(ApplicationContext context) {
        return context instanceof WebApplicationContext;
    }

    static String get(ApplicationContext context, String path) throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build();
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get(path)).andReturn();
        int status = result.getResponse().getStatus();
        if (status != 200) {
            throw new IllegalStateException("GET " + path + " respondeu " + status);
        }
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
