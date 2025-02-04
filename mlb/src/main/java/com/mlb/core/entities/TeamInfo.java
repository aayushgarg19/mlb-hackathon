package com.mlb.core.entities;

import lombok.Data;

@Data
public class TeamInfo {
    private int score;
    private boolean isWinner;
    private Team team;
}
