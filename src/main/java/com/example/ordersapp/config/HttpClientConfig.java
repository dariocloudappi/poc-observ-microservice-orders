package com.example.ordersapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Clientes HTTP salientes del servicio.
 *
 * Este servicio tiene dos dependencias de salida y cada una lleva su propio
 * RestTemplate, para poder identificarlas por separado en la telemetría:
 *
 *   - microservice-users, la validación de cada pedido. Es la importante.
 *   - httpbin.org, el check de salida a internet de /status.
 *
 * El RestTemplate de users se construye en UserValidationService, porque
 * necesita la autenticación Basic y sus propios timeouts. Aquí vive solo el de
 * httpbin.
 *
 * No hay nada de OpenTelemetry: el agente instrumenta RestTemplate por su cuenta
 * y genera el span de cliente. El interceptor solo añade lo que el agente no
 * cubre, el cuerpo, y marca la llamada con el nombre de la dependencia.
 *
 * BufferingClientHttpRequestFactory es imprescindible cuando se registran
 * cuerpos: envuelve la respuesta para poder leerla dos veces. Sin ella, el
 * interceptor consume el stream y el llamante recibe un cuerpo vacío.
 */
@Configuration
public class HttpClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;

    @Bean
    public RestTemplate httpBinRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);

        RestTemplate restTemplate = new RestTemplate(new BufferingClientHttpRequestFactory(factory));
        // logBodies en true: httpbin.org no devuelve datos de nadie.
        restTemplate.getInterceptors().add(new OutboundHttpLoggingInterceptor("httpbin", true));
        return restTemplate;
    }
}
