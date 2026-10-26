package com.facturaflow.invoicing;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Levanta un PostgreSQL y un RabbitMQ desechables para los tests. {@link ServiceConnection} hace que Spring use
 * la URL y credenciales de PostgreSQL en lugar de las variables de entorno de application.yml.
 *
 * <p>RabbitMQ usa la misma imagen que docker compose y se configura con el MISMO script de init
 * ({@code infra/rabbitmq/init/create-service-users.sh}): vhost {@code facturaflow}, exchange {@code sales.events} y
 * los usuarios {@code sales} e {@code invoicing} con sus permisos mínimos. Invoicing se conecta como
 * {@code invoicing}, así los tests fallan si la topología necesita un permiso que el usuario real no tiene (ADR
 * 0014); para publicar como el productor, {@link #salesTemplate} usa el usuario {@code sales}. Las contraseñas son
 * de prueba, solo viven en este contenedor desechable.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String SALES_EVENTS = "sales.events";
    public static final String VHOST = "facturaflow";
    private static final String ADMIN_PASSWORD = "admin-de-prueba";
    private static final String SALES_PASSWORD = "sales-de-prueba";
    private static final String INVOICING_PASSWORD = "invoicing-de-prueba";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
    }

    @Bean
    RabbitMQContainer rabbitContainer() {
        RabbitMQContainer rabbit = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.6-management-alpine"))
                .withAdminUser("admin").withAdminPassword(ADMIN_PASSWORD)
                .withCopyFileToContainer(MountableFile.forHostPath("../../infra/rabbitmq/init/create-service-users.sh"),
                        "/init/create-service-users.sh");
        rabbit.start();
        try {
            Container.ExecResult result = rabbit.execInContainer("env",
                    "RABBITMQADMIN_BASE_URI=http://localhost:15672/api", "RABBITMQADMIN_USERNAME=admin",
                    "RABBITMQADMIN_PASSWORD=" + ADMIN_PASSWORD, "RABBITMQ_VHOST=" + VHOST,
                    "SALES_RABBITMQ_PASSWORD=" + SALES_PASSWORD, "INVOICING_RABBITMQ_PASSWORD=" + INVOICING_PASSWORD,
                    "sh", "/init/create-service-users.sh");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("Falló el script de init de RabbitMQ: " + result.getStderr());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return rabbit;
    }

    /** Invoicing se conecta con su usuario real de permisos mínimos, no con el administrador. */
    @Bean
    DynamicPropertyRegistrar rabbitProperties(RabbitMQContainer rabbit) {
        return registry -> {
            registry.add("spring.rabbitmq.host", rabbit::getHost);
            registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
            registry.add("spring.rabbitmq.virtual-host", () -> VHOST);
            registry.add("spring.rabbitmq.username", () -> "invoicing");
            registry.add("spring.rabbitmq.password", () -> INVOICING_PASSWORD);
        };
    }

    /** Un RabbitTemplate que publica como sales: solo puede escribir en sales.events, igual que el servicio real. */
    public static RabbitTemplate salesTemplate(RabbitMQContainer rabbit) {
        CachingConnectionFactory connections = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
        connections.setVirtualHost(VHOST);
        connections.setUsername("sales");
        connections.setPassword(SALES_PASSWORD);
        return new RabbitTemplate(connections);
    }
}
