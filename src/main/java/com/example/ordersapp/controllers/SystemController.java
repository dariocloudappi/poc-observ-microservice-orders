package com.example.ordersapp.controllers;

import com.example.ordersapp.system.SystemService;
import com.example.ordersapp.system.SystemStatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/status")
public class SystemController {
    private final SystemService systemService;

    public SystemController(SystemService systemService) {
        this.systemService = systemService;
    }

    @GetMapping
    public SystemStatusResponse getStatus() {
        return systemService.getStatus();
    }
}
