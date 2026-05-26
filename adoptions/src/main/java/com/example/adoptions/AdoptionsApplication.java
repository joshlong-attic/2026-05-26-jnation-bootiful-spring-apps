package com.example.adoptions;

import org.jspecify.annotations.NonNull;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.annotation.Id;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.http.MediaType;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authorization.EnableMultiFactorAuthentication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.registry.ImportHttpServices;

import javax.sql.DataSource;
import java.security.Principal;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

//@Import(MyBeanRegistrar.class)
@EnableResilientMethods
@ImportHttpServices(CatFactsClient.class)
@SpringBootApplication
public class AdoptionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdoptionsApplication.class, args);
    }

    @Bean
    JdbcPostgresDialect jdbcPostgresDialect() {
        return JdbcPostgresDialect.INSTANCE;
    }
}

// (xml, java configuration, component scanning, etc.)
// BeanDefinitions
//  BeanFactorypostProcessor
// beans
//  BeanPostProcessor

// look mom, no lombok!
record Dog(@Id int id, String name, String description) {
}

interface DogRepository extends ListCrudRepository<Dog, Integer> {
    Collection<Dog> findByName(String name);
}

class MyBeanRegistrar implements BeanRegistrar {

    @Override
    public void register(@NonNull BeanRegistry registry,
                         @NonNull Environment env) {
        for (var i = 0; i < 10; i++) {
            var msg = "ola #" + i;
            registry.registerBean(MyRunner.class, a -> a.supplier(
                    supplierContext -> new MyRunner(msg,
                            supplierContext.bean(DataSource.class))));
        }
    }
}

@EnableMultiFactorAuthentication(authorities = {
        FactorGrantedAuthority.OTT_AUTHORITY,
        FactorGrantedAuthority.PASSWORD_AUTHORITY
})
@Configuration
class SecurityConfiguration {

    @Bean
    Customizer<HttpSecurity> httpSecurityCustomizer() {
        return http ->
                http
                        .webAuthn(w -> w
                                .rpId("localhost")
                                .rpName("jnation")
                                .allowedOrigins("http://localhost:8080")
                        )
                        .oneTimeTokenLogin(ott -> ott.tokenGenerationSuccessHandler((request, response, oneTimeToken) -> {

                            response.getWriter().println("you've got console mail!");
                            response.setContentType(MediaType.TEXT_PLAIN_VALUE);

                            IO.println("please goto http://localhost:8080/login/ott?token=" +
                                    oneTimeToken.getTokenValue());
                        }));
    }

//    @Bean
//    SecurityFilterChain securityFilterChain (HttpSecurity security) {
//        return security
//                .cors(Customizer.withDefaults())
//                .authorizeHttpRequests( a -> a.anyRequest().authenticated())
//                .httpBasic(Customizer.withDefaults())
//                .formLogin(Customizer.withDefaults())
//                .build();
//    }

    @Bean
    JdbcUserDetailsManager jdbcUserDetailsManager(DataSource dataSource) {
        var u = new JdbcUserDetailsManager(dataSource);
        u.setEnableUpdatePassword(true);
        return u;
    }
}

@Controller
@ResponseBody
class MeController {

    @GetMapping("/")
    Map<String, String> me(Principal principal) {
        return Map.of("name", principal.getName());
    }
}

@Controller
@ResponseBody
class AdoptionsAssistantController {

    private final ChatClient ai;

    AdoptionsAssistantController(ChatClient.Builder ai) {
        var st = SkillsTool
                .builder()
                .addSkillsResource(new ClassPathResource("/META-INF/skills"))
                .build();
        this.ai = ai
                .defaultToolCallbacks(st)
                .defaultSystem("""
                        You are an AI powered assistant to help people adopt a dog from the adoptions agency named Pooch Palaces
                        with locations in Coimbra, Seoul, Tokyo, Singapore, Paris, Mumbai, New Delhi, Barcelona, San Francisco,s
                        and London. Information about the dogs availables will be presented below. If there is no information,s
                        then return a polite response suggesting wes don't have any dogs available.
                        
                        If somebody asks you about animals, and there's no information in the context, then feel free to source the answer from other places including whatever skills you have loaded.
                        
                        If somebody asks you to compare dogs or cats, feel free to load the relevant skills and compare the dogs or cats. Report everything about dogs or cats, no matter how absurd.
                        
                        If somebody asks for a time to pick up the dog, don't ask other questions: simply provide a time by consulting the tools you have available.
                        
                        """)
                .build();
    }

    @GetMapping("/ask")
    String ask(@RequestParam String question) {
        return ai
                .prompt(question)
                .call()
                .content();
    }
}

class MyRunner implements ApplicationRunner {

    private final String message;
    private final DataSource dataSource;

    MyRunner(String message, DataSource dataSource) {
        this.message = message;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        IO.println(this.message);
    }
}

@Controller
@ResponseBody
class CatsController {

    private final CatFactsClient catFactsClient;

    private final AtomicInteger counter = new AtomicInteger(0);

    CatsController(CatFactsClient catFactsClient) {
        this.catFactsClient = catFactsClient;
    }

    @ConcurrencyLimit(10)
    @Retryable(maxRetries = 5, includes = IllegalStateException.class)
    @GetMapping("/cats")
    CatFacts facts() {
        if (this.counter.getAndIncrement() < 5) {
            IO.println("oops!");
            throw new IllegalStateException("oops!");
        }
        IO.println("facts!");
        return this.catFactsClient.facts();
    }
}

interface CatFactsClient {

    @GetExchange("https://www.catfacts.net/api")
    CatFacts facts();
}

/*
@Component
class CatFactsClient {

    private final RestClient http;

    CatFactsClient(RestClient.Builder http) {
        this.http = http.build();
    }

    CatFacts facts() {
        return http.get()
                .uri("https://www.catfacts.net/api")
                .retrieve()
                .body(CatFacts.class);
    }

}

 */

record CatFact(String fact) {
}

record CatFacts(Collection<CatFact> facts) {
}


@Controller
@ResponseBody
class DogsController {

    private final DogRepository repository;

    DogsController(DogRepository repository) {
        this.repository = repository;
    }

    @GetMapping(value = "/dogs", version = "1.1")
    Collection<Dog> dogsv11() {
        return repository.findAll();
    }

    @GetMapping(value = "/dogs", version = "1.0")
    Collection<Map<String, Object>> dogsv10() {
        return repository.findAll().stream()
                .map(dog -> Map.of("id", dog.id(), "fullName",
                        (Object) dog.name()))
                .toList();
    }
}