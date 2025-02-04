package com.mlb.core.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class MLBGameFeed {
    @JsonProperty("gameData")
    private GameData gameData;
    @JsonProperty("liveData")
    private LiveData liveData;

    @Data
    public static class GameData {
        private Teams teams;
        private Game game;
    }

    @Data
    public static class Teams {
        private Team away;
        private Team home;
    }

    @Data
    public static class Team {
        private String name;
        private TeamRecord record;
    }

    @Data
    public static class TeamRecord {
        private int wins;
        private int losses;
    }

    @Data
    public static class Game {
        private String gameDate;
        private String pk;
    }

    @Data
    public static class LiveData {
        private Plays plays;
        private Linescore linescore;
    }
    @Data
    public static class Linescore {
        private Teams teams;
        private int currentInning;
        private String inningState;
        private boolean isTopInning;
        private Defense defense;
        private Offense offense;

        @Data
        public static class Teams {
            private TeamScore home;
            private TeamScore away;
        }

        @Data
        public static class TeamScore {
            private int runs;
        }

        @Data
        public static class Defense {
            private Player pitcher;
        }

        @Data
        public static class Offense {
            private Player batter;
        }

        @Data
        public static class Player {
            private String fullName;
            private Hand pitchHand;
            private Hand batSide;
        }

        @Data
        public static class Hand {
            private String description;
            private String code;
        }
    }

    @Data
    public static class Plays {
        private List<PlayEvent> allPlays;
        private PlayEvent currentPlay;
    }

    @Data
    public static class PlayEvent {
        private Result result;
        private About about;
        @JsonProperty("matchup")
        private Matchup matchup;
        private Count count;
    }

    @Data
    public static class Count {
        private int balls;
        private int strikes;
        private int outs;
        private int pitches;
    }

    @Data
    public static class Result {
        private String description;
        private String event;
        private String eventType;
        private Integer homeScore;
        private Integer awayScore;
    }

    @Data
    public static class About {
        private int inning;
        private boolean isTopInning;
        private Long timestamp;
    }
    @Data
    public static class Matchup {
        private Batter batter;
        private Pitcher pitcher;
    }

    @Data
    public static class Batter {
        private String fullName;
        private Stats stats;
        private BatSide batSide;
    }

    @Data
    public static class Pitcher {
        private String fullName;
        private Stats stats;
        private PitchHand pitchHand;
    }

    @Data
    public static class BatSide {
        private String code;
        private String description;
    }

    @Data
    public static class PitchHand {
        private String code;
        private String description;
    }

    @Data
    public static class Stats {
        private Batting batting;
        private Pitching pitching;
    }

    @Data
    public static class Batting {
        private double avg;
        private int homeRuns;
        private int rbi;
    }

    @Data
    public static class Pitching {
        private double era;
        private int wins;
        private int losses;
        private int strikeOuts;
    }
}