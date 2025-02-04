package com.mlb.core.entities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DateSchedule {
    private String date;
    private List<Game> games;
    private int totalGames;
    private int totalGamesInProgress;
}
