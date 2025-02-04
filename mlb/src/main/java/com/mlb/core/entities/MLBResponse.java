package com.mlb.core.entities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MLBResponse {
    private String copyright;
    private int totalItems;
    private int totalEvents;
    private int totalGames;
    private int totalGamesInProgress;
    private List<DateData> dates;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DateData {
        private String date;
        private int totalItems;
        private int totalEvents;
        private int totalGames;
        private int totalGamesInProgress;
        private List<GameData> games;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GameData {
        private long gamePk;
        private String gameGuid;
        private String link;
        private String gameType;
        private String season;
        private String gameDate;
        private String officialDate;
        private GameStatus status;
        private Teams teams;
        private Venue venue;
        private Content content;
        private boolean isTie;
        private int gameNumber;
        private boolean publicFacing;
        private String doubleHeader;
        private String gamedayType;
        private String tiebreaker;
        private String calendarEventID;
        private String seasonDisplay;
        private String dayNight;
        private int scheduledInnings;
        private boolean reverseHomeAwayStatus;
        private int inningBreakLength;
        private int gamesInSeries;
        private int seriesGameNumber;
        private String seriesDescription;
        private String recordSource;
        private String ifNecessary;
        private String ifNecessaryDescription;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GameStatus {
        private String abstractGameState;
        private String codedGameState;
        private String detailedState;
        private String statusCode;
        private boolean startTimeTBD;
        private String abstractGameCode;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Teams {
        private TeamInfo away;
        private TeamInfo home;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TeamInfo {
        private LeagueRecord leagueRecord;
        private int score;
        private Team team;
        private boolean isWinner;
        private boolean splitSquad;
        private int seriesNumber;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LeagueRecord {
        private int wins;
        private int losses;
        private String pct;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Team {
        private int id;
        private String name;
        private String link;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Venue {
        private int id;
        private String name;
        private String link;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Content {
        private String link;
    }
}
