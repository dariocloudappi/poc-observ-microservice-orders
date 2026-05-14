package com.example.ordersapp.system;

import java.util.List;

public class SystemStatusResponse {
    private List<ServiceStatus> services;

    public SystemStatusResponse(List<ServiceStatus> services) {
        this.services = services;
    }

    public List<ServiceStatus> getServices() {
        return services;
    }

    public void setServices(List<ServiceStatus> services) {
        this.services = services;
    }
}