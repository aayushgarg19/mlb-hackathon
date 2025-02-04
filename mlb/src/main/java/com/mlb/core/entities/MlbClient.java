package com.mlb.core.entities;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
public class MlbClient {

    private final RestTemplate restTemplate;
    private final String baseUrl = "https://statsapi.mlb.com/api/v1";

    public MlbClient(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }


    public SeasonSchedule getSeasonSchedule(int season) {
        String url = baseUrl + "/schedule?sportId=1&season=" + season;
        ResponseEntity<SeasonSchedule> response = restTemplate.getForEntity(url, SeasonSchedule.class);
        return response.getBody();
    }
}
