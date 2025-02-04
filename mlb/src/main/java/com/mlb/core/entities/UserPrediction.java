package com.mlb.core.entities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserPrediction {
    private String prediction;
    private LocalDateTime predictionTime;
    private int playIndex;
}
