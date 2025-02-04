package com.mlb.core.entities;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class GumboDataService {
    private final WebClient webClient;
    private static final String MLB_API_BASE_URL = "https://statsapi.mlb.com/api/v1.1";
    private static final Duration POLL_INTERVAL = Duration.ofMinutes(1);
    private final PersonalMlbCommentator personalMlbCommentator;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private String lastTimestamp = null;
    private List<String> timestampCache = new ArrayList<>();
    private int currentTimestampIndex = -1;
    private Queue<MLBGameEvent> eventQueue = new LinkedList<>();

    // Add subscriber tracking
    private final AtomicInteger activeSubscribers = new AtomicInteger(0);
    private volatile boolean isStreamActive = false;

    @Autowired
    public GumboDataService(WebClient.Builder webClientBuilder, PersonalMlbCommentator personalMlbCommentator,
                            ObjectMapper objectMapper, RestTemplate restTemplate) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .build();

        this.webClient = webClientBuilder
                .baseUrl(MLB_API_BASE_URL)
                .exchangeStrategies(strategies)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "Spring Boot Application")
                .build();
        this.personalMlbCommentator = personalMlbCommentator;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    private Mono<String> getNextTimestamp() {
        // Check if we need to fetch new timestamps
        if (shouldFetchNewTimestamps()) {
            // Fetch new timestamps from MLB API
            return fetchTimestamps()
                    .map(timestamps -> {
                        if (timestamps.isEmpty()) {
                            log.debug("No new timestamps available");
                            return null;
                        }
                        // Store new timestamps in cache
                        timestampCache = timestamps;
                        // Reset index to start of new cache
                        currentTimestampIndex = 0;
                        // Return first timestamp from new cache
                        return timestampCache.get(0);
                    });
        } else {
            // Move to next timestamp in existing cache
            currentTimestampIndex++;
            log.debug("Using cached timestamp at index: {}", currentTimestampIndex);
            return Mono.just(timestampCache.get(currentTimestampIndex));
        }
    }

    /**
     * Determines if we need to fetch new timestamps
     */
    private boolean shouldFetchNewTimestamps() {
        return timestampCache.isEmpty() ||                    // Cache is empty
                currentTimestampIndex >= timestampCache.size() - 1;  // Reached end of cache
    }

    // Subscriber management methods
    private void incrementSubscribers() {
        int count = activeSubscribers.incrementAndGet();
        isStreamActive = true;
        log.info("New subscriber connected. Total subscribers: {}", count);
    }

    private void decrementSubscribers() {
        int count = activeSubscribers.decrementAndGet();
        log.info("Subscriber disconnected. Total subscribers: {}", count);
        if (count == 0) {
            isStreamActive = false;
            resetState();
            log.info("All subscribers disconnected. Stopping event generation.");
        }
    }

    private void resetState() {
        timestampCache.clear();
        currentTimestampIndex = -1;
        eventQueue.clear();
        lastTimestamp = null;
    }

    public Flux<MLBGameEvent> getLiveFeedStream() {
        return Flux.create(sink -> {
            incrementSubscribers();

            Disposable subscription = Flux.interval(Duration.ZERO, POLL_INTERVAL)
                    .takeWhile(tick -> isStreamActive)
                    .flatMap(tick -> {
                        if (!isStreamActive) {
                            return Mono.empty();
                        }

                        if (!eventQueue.isEmpty()) {
                            return Mono.just(eventQueue.poll());
                        }

                        return getNextTimestamp()
                                .flatMap(timestamp -> processTimestamp(timestamp));
                    })
                    .subscribe(
                            event -> sink.next(event),
                            error -> {
                                log.error("Error in live feed stream: ", error);
                                sink.error(error);
                                decrementSubscribers();
                            },
                            () -> {
                                log.info("Live feed stream completed");
                                sink.complete();
                                decrementSubscribers();
                            }
                    );

            // Handle client disconnection
            sink.onCancel(() -> {
                subscription.dispose();
                decrementSubscribers();
            });
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private Mono<MLBGameEvent> processTimestamp(String timestamp) {
        if (timestamp != null && !timestamp.equals(lastTimestamp)) {
            lastTimestamp = timestamp;
            return fetchLiveFeed(timestamp)
                    .collectList()
                    .flatMap(events -> {
                        eventQueue.addAll(events);
                        return Mono.justOrEmpty(eventQueue.poll());
                    });
        }
        return Mono.empty();
    }

    private Mono<List<String>> fetchTimestamps() {
        if (!isStreamActive) {
            return Mono.empty();
        }

        return webClient.get()
                .uri("/game/{gameId}/feed/live/timestamps", getCurrentGameId())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<String>>() {})
                .map(this::processTimestamps)
                .doOnError(error -> log.error("Error fetching timestamps: ", error));
    }

    private List<String> processTimestamps(List<String> timestamps) {
        if (timestamps == null || timestamps.isEmpty()) {
            return new ArrayList<>();
        }

        if (lastTimestamp != null) {
            int startIndex = timestamps.indexOf(lastTimestamp);
            if (startIndex >= 0 && startIndex < timestamps.size() - 1) {
                return timestamps.subList(startIndex + 1, timestamps.size());
            }
        }
        return timestamps;
    }

    private Flux<MLBGameEvent> fetchLiveFeed(String timestamp) {
        if (!isStreamActive) {
            return Flux.empty();
        }

        return webClient.get()
                .uri("/game/{gameId}/feed/live?timecode={timestamp}",
                        getCurrentGameId(),
                        timestamp)
                .retrieve()
                .bodyToMono(MLBGameFeed.class)
                .map(this::createGameEvent)
                .flatMapMany(Flux::fromIterable)
                .doOnNext(event -> {
                    if (isStreamActive) {
                        event.setTimestamp(timestamp);
                        log.info("Received game event: {} at timestamp: {}", event.getType(), timestamp);
                    }
                })
                .doOnError(error -> log.error("Error fetching live feed: ", error));
    }

    private List<MLBGameEvent> createGameEvent(MLBGameFeed feed) {
        if (!isStreamActive) {
            return Collections.emptyList();
        }

        List<MLBGameEvent> events = new ArrayList<>();
        MLBGameFeed.LiveData liveData = feed.getLiveData();
        MLBGameFeed.GameData gameData = feed.getGameData();
        MLBGameFeed.Linescore linescore = liveData.getLinescore();

        if (liveData.getPlays() != null && liveData.getPlays().getAllPlays() != null) {
            for (MLBGameFeed.PlayEvent play : liveData.getPlays().getAllPlays()) {
                if (!isStreamActive) break;  // Stop processing if no subscribers

                MLBGameEvent event = convertToGameEvent(play);
                if (isValidEvent(event)) {
                    try {
                        Map<String, Object> gameContext = buildGameContext(play, linescore, gameData);

                        if (isStreamActive) {  // Check again before making LLM call
                            String gameContextJson = objectMapper.writeValueAsString(gameContext);
                            String chat = personalMlbCommentator.chat("riaz", gameContextJson);
                            event.setDescription(chat);
                            events.add(event);
                        }
                    } catch (JsonProcessingException e) {
                        log.error("Error processing game event: ", e);
                    }
                }
            }
        }
        return events;
    }

    private Map<String, Object> buildGameContext(MLBGameFeed.PlayEvent play,
                                                 MLBGameFeed.Linescore linescore, MLBGameFeed.GameData gameData) {
        Map<String, Object> gameContext = new HashMap<>();

        // Game / Inning Info
        gameContext.put("currentInning", linescore.getCurrentInning());
        gameContext.put("inningState", getInningState(play.getAbout().isTopInning()));
        gameContext.put("isTopInning", play.getAbout().isTopInning());

        // Score
        Map<String, Integer> score = new HashMap<>();
        score.put("away", linescore.getTeams().getAway().getRuns());
        score.put("home", linescore.getTeams().getHome().getRuns());
        gameContext.put("score", score);

        // Team Names
        gameContext.put("awayTeam", gameData.getTeams().getAway().getName());
        gameContext.put("homeTeam", gameData.getTeams().getHome().getName());

        // Add pitcher and batter info
        addPitcherInfo(gameContext, linescore);
        addBatterInfo(gameContext, linescore, play);

        // Play Context
        gameContext.put("playEvent", play.getResult().getEvent());
        gameContext.put("playDescription", play.getResult().getDescription());

        // Count
        addCountInfo(gameContext, play);

        return gameContext;
    }

    private String getCurrentGameId() {
        return "775296";
    }


    private String getInningState(boolean isTopInning) {
        return isTopInning ? "Top" : "Bottom";
    }

    private boolean isValidEvent(MLBGameEvent event) {
        // Filter out events with no meaningful information
        if (event.getType() == null || event.getType().isEmpty()) {
            return false;
        }

        // For game_advisory events, ensure they have a description
        if ("game_advisory".equals(event.getType()) &&
                (event.getDescription() == null || event.getDescription().isEmpty())) {
            return false;
        }

        // For GAME_STATUS events, ensure they have team names
        if ("GAME_STATUS".equals(event.getType()) &&
                (event.getHomeTeam() == null || event.getAwayTeam() == null)) {
            return false;
        }

        return true;
    }

    private MLBGameEvent convertToGameEvent(MLBGameFeed.PlayEvent play) {
        MLBGameEvent event = new MLBGameEvent();

        // Set basic information
        event.setType(play.getResult().getEventType() != null ?
                play.getResult().getEventType().toLowerCase() : "game_advisory");
        event.setDescription(play.getResult().getDescription());
        event.setInning(play.getAbout().getInning());
        event.setTopInning(play.getAbout().isTopInning());
        event.setResult(play.getResult().getEvent());

        // Only set player names if they exist
        if (play.getMatchup() != null) {
            if (play.getMatchup().getBatter() != null) {
                event.setBatterName(play.getMatchup().getBatter().getFullName());
            }
            if (play.getMatchup().getPitcher() != null) {
                event.setPitcherName(play.getMatchup().getPitcher().getFullName());
            }
        }

        // Set count information if it exists
        if (play.getCount() != null) {
            event.setBalls(play.getCount().getBalls());
            event.setStrikes(play.getCount().getStrikes());
            event.setOuts(play.getCount().getOuts());
        }

        // Set score if available
        if (play.getResult().getHomeScore() != null) {
            event.setHomeScore(play.getResult().getHomeScore());
            event.setAwayScore(play.getResult().getAwayScore());
        }

        return event;
    }

    public MLBResponse getSchedule(String startDate, String endDate) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl("https://statsapi.mlb.com/api/v1/schedule/")
                .queryParam("sportId", 1)
                .queryParam("season", 2024)
                .queryParam("startDate", startDate)
                .queryParam("endDate", endDate);

        RequestEntity<?> request = RequestEntity
                .get(builder.build().toUri())
                .headers(headers)
                .build();

        try {
            ResponseEntity<MLBResponse> response = restTemplate.exchange(
                    request,
                    MLBResponse.class
            );

            return response.getBody();
        }catch (Exception e) {
            log.error("Unexpected error while fetching MLB schedule: {}", e.getMessage());
            throw new RuntimeException("Failed to fetch MLB schedule", e);
        }
    }

    private void addPitcherInfo(Map<String, Object> gameContext, MLBGameFeed.Linescore linescore) {
        if (linescore.getDefense() != null && linescore.getDefense().getPitcher() != null) {
            gameContext.put("currentPitcher", linescore.getDefense().getPitcher().getFullName());
            if (linescore.getDefense().getPitcher().getPitchHand() != null) {
                gameContext.put("pitcherHand", linescore.getDefense().getPitcher().getPitchHand().getDescription());
            }
        }
    }

    private void addBatterInfo(Map<String, Object> gameContext, MLBGameFeed.Linescore linescore,
                               MLBGameFeed.PlayEvent play) {
        if (linescore.getOffense() != null && linescore.getOffense().getBatter() != null) {
            gameContext.put("currentBatter", linescore.getOffense().getBatter().getFullName());
            if (play.getMatchup().getBatter().getBatSide() != null) {
                gameContext.put("batterSide", play.getMatchup().getBatter().getBatSide().getDescription());
            }
        }
    }

    private void addCountInfo(Map<String, Object> gameContext, MLBGameFeed.PlayEvent play) {
        if (play.getCount() != null) {
            Map<String, Integer> count = new HashMap<>();
            count.put("balls", play.getCount().getBalls());
            count.put("strikes", play.getCount().getStrikes());
            count.put("outs", play.getCount().getOuts());
            gameContext.put("count", count);
        }
    }
}