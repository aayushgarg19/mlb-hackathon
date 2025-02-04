package com.mlb.core.entities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;


@Slf4j
@Service
public class MLBGameService {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private static final String MLB_API_BASE_URL = "https://statsapi.mlb.com/api/v1.1/game/";
    private final PersonalMlbCommentator personalMlbCommentator;


    @Autowired
    public MLBGameService(RestTemplate restTemplate, ObjectMapper objectMapper, PersonalMlbCommentator personalMlbCommentator) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.personalMlbCommentator = personalMlbCommentator;
    }


    @Data
    public static class GameEventWithStatus {
        private MLBGameEvent event;
        private LiveGameStatus status;
        private UserPrediction userPrediction;
    }


    private final Map<String, UserPrediction> userPredictions = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<UserPrediction>> pendingPredictions = new ConcurrentHashMap<>();

    public void saveUserPrediction(String userId, String gameId, String predictionText) {
        String key = userId + "-" + gameId;

        // Get current play index
        int currentPlayIndex = getCurrentPlayIndex(gameId);

        UserPrediction prediction = new UserPrediction();
        prediction.setPrediction(predictionText);
        prediction.setPredictionTime(LocalDateTime.now());
        prediction.setPlayIndex(currentPlayIndex);

        // Update the prediction in the map
        userPredictions.put(key, prediction);
        log.info("New prediction saved for user {} at play index {}: {}", userId, currentPlayIndex, predictionText);

        // Complete the future if it exists (for initial prediction)
        CompletableFuture<UserPrediction> future = pendingPredictions.get(key);
        if (future != null) {
            future.complete(prediction);
        }
    }

    private void processPlay(
            MLBGameFeed.PlayEvent play,
            MLBGameFeed gameFeed,
            String userId,
            String gameId,
            int awayScore,
            int homeScore,
            SseEmitter emitter) throws IOException {

        try {
            // Get the latest prediction for this user
            String key = userId + "-" + gameId;
            UserPrediction currentPrediction = userPredictions.get(key);

            Map<String, Object> context = createEnhancedContext(
                    gameFeed, play, currentPrediction, awayScore, homeScore);

            String chat = personalMlbCommentator.chat("riaz",
                    objectMapper.writeValueAsString(context));

            MLBGameEvent event = convertToGameEvent(play);
            event.setDescription(chat);
            event.setHomeScore(homeScore);
            event.setAwayScore(awayScore);

            LiveGameStatus status = createLiveGameStatus(gameFeed, play, awayScore, homeScore);

            GameEventWithStatus eventWithStatus = new GameEventWithStatus();
            eventWithStatus.setEvent(event);
            eventWithStatus.setStatus(status);
            eventWithStatus.setUserPrediction(currentPrediction);

            sendEventToClient(emitter, eventWithStatus);
        } catch (Exception e) {
            log.error("Error processing play: ", e);
        }
    }

    private void streamGameWithPrediction(String gameId, String userId, UserPrediction initialPrediction, SseEmitter emitter) {
        try {
            String url = MLB_API_BASE_URL + gameId + "/feed/live";
            MLBGameFeed gameFeed = restTemplate.getForObject(url, MLBGameFeed.class);

            if (gameFeed == null || gameFeed.getLiveData() == null) {
                emitter.completeWithError(new RuntimeException("Unable to fetch game data"));
                return;
            }

            // Store initial prediction
            if (initialPrediction != null) {
                String key = userId + "-" + gameId;
                userPredictions.put(key, initialPrediction);
            }

            sendGameMetadata(emitter, gameFeed, initialPrediction);

            int[] scores = new int[2];  // [homeScore, awayScore]
            List<MLBGameFeed.PlayEvent> plays = gameFeed.getLiveData().getPlays().getAllPlays();

            for (MLBGameFeed.PlayEvent play : plays) {
                updateScores(play, scores);
                processPlay(play, gameFeed, userId, gameId, scores[1], scores[0], emitter);
                TimeUnit.MINUTES.sleep(1);
            }

            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data("Game replay completed"));
            emitter.complete();

        } catch (Exception e) {
            log.error("Error streaming game: ", e);
            emitter.completeWithError(e);
        }
    }

    @Async
    public void streamGame(String gameId, String userId, SseEmitter emitter) {
        try {
            UserPrediction prediction = userPredictions.get(userId + "-" + gameId);

            if (prediction == null) {
                CompletableFuture<UserPrediction> predictionFuture = new CompletableFuture<>();
                pendingPredictions.put(userId + "-" + gameId, predictionFuture);

                emitter.send(SseEmitter.event()
                        .name("request_prediction")
                        .data("Please make your prediction for the game"));

                try {
                    prediction = predictionFuture.get(60, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    log.warn("No prediction received within timeout for user {} game {}", userId, gameId);
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error waiting for prediction", e);
                    emitter.completeWithError(e);
                    return;
                } finally {
                    pendingPredictions.remove(userId + "-" + gameId);
                }
            }

            streamGameWithPrediction(gameId, userId, prediction, emitter);
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }


    private int getCurrentPlayIndex(String gameId) {
        try {
            String url = MLB_API_BASE_URL + gameId + "/feed/live";
            MLBGameFeed gameFeed = restTemplate.getForObject(url, MLBGameFeed.class);

            if (gameFeed != null && gameFeed.getLiveData() != null &&
                    gameFeed.getLiveData().getPlays() != null) {
                List<MLBGameFeed.PlayEvent> plays = gameFeed.getLiveData().getPlays().getAllPlays();
                return plays.size() - 1;  // Current play index
            }
        } catch (Exception e) {
            log.error("Error getting current play index", e);
        }
        return 0;
    }


    private void updateScores(MLBGameFeed.PlayEvent play, int[] scores) {
        if (play.getResult().getHomeScore() != null) {
            scores[0] = play.getResult().getHomeScore();  // home score
        }
        if (play.getResult().getAwayScore() != null) {
            scores[1] = play.getResult().getAwayScore();  // away score
        }
    }


    private void sendEventToClient(SseEmitter emitter, GameEventWithStatus eventWithStatus) throws IOException {
        try {
            emitter.send(SseEmitter.event()
                    .name("play")
                    .data(objectMapper.writeValueAsString(eventWithStatus)));
        } catch (Exception e) {
            log.error("Error sending event to client: ", e);
            throw e;
        }
    }


    private Map<String, Object> createEnhancedContext(
            MLBGameFeed gameFeed,
            MLBGameFeed.PlayEvent play,
            UserPrediction prediction,
            int currentAwayScore,
            int currentHomeScore) {

        Map<String, Object> context = new HashMap<>();

        // Basic game context
        context.put("currentInning", play.getAbout().getInning());
        context.put("isTopInning", play.getAbout().isTopInning());
        context.put("currentScore", Map.of(
                "away", currentAwayScore,
                "home", currentHomeScore
        ));

        // Teams
        context.put("awayTeam", gameFeed.getGameData().getTeams().getAway().getName());
        context.put("homeTeam", gameFeed.getGameData().getTeams().getHome().getName());

        // Current play
        context.put("playEvent", play.getResult().getEvent());
        context.put("playDescription", play.getResult().getDescription());

        // Add user's prediction as is
        if (prediction != null && prediction.getPrediction() != null) {
            context.put("userPrediction", prediction.getPrediction());
        }

        return context;
    }

    private void sendGameMetadata(SseEmitter emitter, MLBGameFeed gameFeed, UserPrediction prediction) throws IOException {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("homeTeam", gameFeed.getGameData().getTeams().getHome().getName());
        metadata.put("awayTeam", gameFeed.getGameData().getTeams().getAway().getName());
        metadata.put("gameDate", gameFeed.getGameData().getGame().getGameDate());
        if (prediction != null) {
            metadata.put("userPrediction", prediction.getPrediction());
        }

        emitter.send(SseEmitter.event()
                .name("metadata")
                .data(objectMapper.writeValueAsString(metadata)));
    }

    private LiveGameStatus createLiveGameStatus(MLBGameFeed gameFeed, MLBGameFeed.PlayEvent currentPlay,
                                                int currentAwayScore, int currentHomeScore) {
        LiveGameStatus status = new LiveGameStatus();

        // Set current inning
        status.setInning(String.format("%s %dth",
                currentPlay.getAbout().isTopInning() ? "Top" : "Bottom",
                currentPlay.getAbout().getInning()));

        // Set away team info
        TeamStatus awayTeam = new TeamStatus();
        awayTeam.setName(gameFeed.getGameData().getTeams().getAway().getName());
        awayTeam.setRecord(String.format("%d-%d",
                gameFeed.getGameData().getTeams().getAway().getRecord().getWins(),
                gameFeed.getGameData().getTeams().getAway().getRecord().getLosses()));
        awayTeam.setScore(currentAwayScore);
        status.setAwayTeam(awayTeam);

        // Set home team info
        TeamStatus homeTeam = new TeamStatus();
        homeTeam.setName(gameFeed.getGameData().getTeams().getHome().getName());
        homeTeam.setRecord(String.format("%d-%d",
                gameFeed.getGameData().getTeams().getHome().getRecord().getWins(),
                gameFeed.getGameData().getTeams().getHome().getRecord().getLosses()));
        homeTeam.setScore(currentHomeScore);
        status.setHomeTeam(homeTeam);

        // Set current pitcher info
        status.setCurrentPitcher(currentPlay.getMatchup().getPitcher().getFullName());

        // Set pitch count from current play
        status.setPitchCount(currentPlay.getCount().getPitches());

        return status;
    }


    public List<MLBGameEvent> fetch(String gameId) {
        String url = MLB_API_BASE_URL + gameId + "/feed/live";
        MLBGameFeed gameFeed = restTemplate.getForObject(url, MLBGameFeed.class);

        if (gameFeed == null || gameFeed.getLiveData() == null ||
                gameFeed.getLiveData().getPlays() == null ||
                gameFeed.getLiveData().getPlays().getAllPlays() == null) {
            throw new RuntimeException("Unable to fetch game data or game data is incomplete");
        }

        List<MLBGameEvent> events = new ArrayList<>();

        // Convert API data to our event model
        for (MLBGameFeed.PlayEvent play : gameFeed.getLiveData().getPlays().getAllPlays()) {
            MLBGameEvent event = convertToGameEvent(play);
            events.add(event);
        }

        // Replay events with simulated timing
        replayEvents(events);

        return events;
    }

    private MLBGameEvent convertToGameEvent(MLBGameFeed.PlayEvent play) {
        MLBGameEvent event = new MLBGameEvent();
        event.setType(play.getResult().getEventType());
        event.setDescription(play.getResult().getDescription());
        event.setInning(play.getAbout().getInning());
        event.setTopInning(play.getAbout().isTopInning());
        event.setBatterName(play.getMatchup().getBatter().getFullName());
        event.setPitcherName(play.getMatchup().getPitcher().getFullName());
        event.setResult(play.getResult().getEvent());
        return event;
    }

    private void replayEvents(List<MLBGameEvent> events) {
        System.out.println("Starting game replay...\n");

        for (MLBGameEvent event : events) {
            String inningHalf = event.isTopInning() ? "Top" : "Bottom";

            System.out.printf("Inning: %s %d\n", inningHalf, event.getInning());
            System.out.printf("At bat: %s vs %s\n", event.getBatterName(), event.getPitcherName());
            System.out.printf("Result: %s\n", event.getDescription());
            System.out.println("----------------------------------------\n");

            try {
                // Add a delay between events to simulate real-time playback
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("Game replay completed!");
    }

    @Data
    public static class LiveGameStatus {
        private String type = "MLB • LIVE";
        private String inning;
        private TeamStatus awayTeam;
        private TeamStatus homeTeam;
        private String currentPitcher;
        private int pitchCount;
    }

    @Data
    public static class TeamStatus {
        private String name;
        private String record;
        private int score;
    }

    public LiveGameStatus getLiveGameStatus(String gameId) {
        String url = MLB_API_BASE_URL + gameId + "/feed/live";
        MLBGameFeed gameFeed = restTemplate.getForObject(url, MLBGameFeed.class);

        if (gameFeed == null || gameFeed.getLiveData() == null) {
            throw new RuntimeException("Unable to fetch game data");
        }

        LiveGameStatus status = new LiveGameStatus();

        // Set current inning
        MLBGameFeed.PlayEvent currentPlay = Optional.ofNullable(gameFeed.getLiveData().getPlays())
                .map(MLBGameFeed.Plays::getCurrentPlay)
                .orElseThrow(() -> new RuntimeException("No current play data available"));

        status.setInning(String.format("%s %dth",
                currentPlay.getAbout().isTopInning() ? "Top" : "Bottom",
                currentPlay.getAbout().getInning()));

        // Set away team info
        TeamStatus awayTeam = new TeamStatus();
        awayTeam.setName("Dodgers"); // Example: should come from gameFeed
        awayTeam.setRecord(String.format("%d-%d",
                gameFeed.getGameData().getTeams().getAway().getRecord().getWins(),
                gameFeed.getGameData().getTeams().getAway().getRecord().getLosses()));
        awayTeam.setScore(gameFeed.getLiveData().getLinescore().getTeams().getAway().getRuns());
        status.setAwayTeam(awayTeam);

        // Set home team info
        TeamStatus homeTeam = new TeamStatus();
        homeTeam.setName("Mets"); // Example: should come from gameFeed
        homeTeam.setRecord(String.format("%d-%d",
                gameFeed.getGameData().getTeams().getHome().getRecord().getWins(),
                gameFeed.getGameData().getTeams().getHome().getRecord().getLosses()));
        homeTeam.setScore(gameFeed.getLiveData().getLinescore().getTeams().getHome().getRuns());
        status.setHomeTeam(homeTeam);

        // Set current pitcher info
        MLBGameFeed.Pitcher currentPitcher = currentPlay.getMatchup().getPitcher();
        status.setCurrentPitcher(
                currentPitcher.getFullName());

        // Set pitch count
        status.setPitchCount(currentPlay.getCount().getPitches());

        return status;
    }
}
