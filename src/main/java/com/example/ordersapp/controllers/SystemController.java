package com.example.ordersapp.controllers;

import com.example.ordersapp.observability.Observability;
import com.example.ordersapp.system.SystemService;
import com.example.ordersapp.system.SystemStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/status")
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);

    private final SystemService systemService;

    public SystemController(SystemService systemService) {
        this.systemService = systemService;
    }

    /**
     * Devuelve 200 siempre, tambien en degradado, y el detalle por dependencia
     * viaja en el cuerpo. Se mantiene asi para no cambiar el contrato: el
     * health check del gateway y la sonda de Container Apps consumen este
     * endpoint.
     *
     * Consecuencia observable: el codigo HTTP NO sirve para alertar. Para eso
     * esta el atributo health.overall que anota SystemService, que si distingue
     * ok de degraded.
     */
    @GetMapping
    public SystemStatusResponse getStatus() {
        Observability.attr("api.operation", "system.status");
        log.debug("Entrando en system.status");
        return systemService.getStatus();
    }
}
