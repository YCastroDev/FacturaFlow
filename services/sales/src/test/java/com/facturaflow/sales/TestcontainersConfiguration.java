package com.facturaflow.sales;

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
 * los usuarios {@code sales} e {@code invoicing} con sus permisos mínimos. Sales se conecta como {@code sales}, así
 * los tests fallan si la topología necesita un permiso que el usuario real no tiene (ADR 0015); para publicar como
 * invoicing (el productor de los resultados), {@link #invoicingTemplate} usa el usuario {@code invoicing}. Las contraseñas son
 * de prueba, solo viven en este contenedor desechable.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String INVOICING_EVENTS = "invoicing.events";
    public static final DockerImageName RABBITMQ_IMAGE = DockerImageName.parse("rabbitmq:4.3.6-management-alpine");
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
        RabbitMQContainer rabbit = new RabbitMQContainer(RABBITMQ_IMAGE)
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

    /** Sales se conecta con su usuario real de permisos mínimos, no con el administrador. */
    @Bean
    DynamicPropertyRegistrar rabbitProperties(RabbitMQContainer rabbit) {
        return registry -> {
            registry.add("spring.rabbitmq.host", rabbit::getHost);
            registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
            registry.add("spring.rabbitmq.virtual-host", () -> VHOST);
            registry.add("spring.rabbitmq.username", () -> "sales");
            registry.add("spring.rabbitmq.password", () -> SALES_PASSWORD);
        };
    }

    /** Un RabbitTemplate que publica como invoicing: puede escribir en invoicing.events, igual que el servicio real. */
    public static RabbitTemplate invoicingTemplate(RabbitMQContainer rabbit) {
        CachingConnectionFactory connections = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
        connections.setVirtualHost(VHOST);
        connections.setUsername("invoicing");
        connections.setPassword(INVOICING_PASSWORD);
        return new RabbitTemplate(connections);
    }
}
