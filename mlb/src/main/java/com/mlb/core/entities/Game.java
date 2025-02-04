package com.mlb.core.entities;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
public class Game {

    private Long id;

    private Long mlbGameId;
    private LocalDate gameDate;

    private String title;


    private Team awayTeam;


    private Team homeTeam;

}
