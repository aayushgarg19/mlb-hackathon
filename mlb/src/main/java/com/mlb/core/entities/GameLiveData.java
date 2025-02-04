package com.mlb.core.entities;

import lombok.Data;

@Data
public class GameLiveData {
    private String type = "MLB • LIVE";
    private String inning;
    private Team awayTeam;
    private Team homeTeam;
    private String currentPitcher;
    private Integer pitchCount;

    @Data
    public static class Team {
        private String name;
        private String record;
        private Integer score;
    }
}
