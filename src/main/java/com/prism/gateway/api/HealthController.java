package com.prism.gateway.api;

import org.springframework.web.bind.annotation.GetMapping;

public class HealthController {

    @GetMapping("health")
    public String health(){
        return "PRISM is running";
    }
}
