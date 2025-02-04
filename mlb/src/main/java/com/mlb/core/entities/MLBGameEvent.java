package com.mlb.core.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class MLBGameEvent {
    private String type;
    private String description;
    private int inning;
    private boolean topInning;
    private String batterName;
    private String pitcherName;
    private String result;
    private String homeTeam;
    private String awayTeam;
    private int homeScore;
    private int awayScore;
    private int balls;
    private int strikes;
    private int outs;
    private String timestamp;
}
