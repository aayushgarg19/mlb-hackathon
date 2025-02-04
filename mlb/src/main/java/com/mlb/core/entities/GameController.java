package com.mlb.core.entities;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequiredArgsConstructor
@RequestMapping("/games")
@CrossOrigin("http://localhost:3000")
public class GameController {

    private final PersonalMlbCommentator agent;


    private final MLBGameService mlbGameService;
    private final GumboDataService dataService;


    @GetMapping(path = "/game/{gameId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamGame(
            @PathVariable String gameId,
            @RequestParam String userId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        mlbGameService.streamGame(gameId, userId, emitter);
        return emitter;
    }

    @PostMapping("/game/{gameId}/predict")
    public ResponseEntity<Void> submitPrediction(
            @PathVariable String gameId,
            @RequestParam String userId,
            @RequestBody Map<String, String> request) {

        String prediction = request.get("prediction");
        if (prediction == null || prediction.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        mlbGameService.saveUserPrediction(userId, gameId, prediction.trim());
        return ResponseEntity.ok().build();
    }


    @PostMapping("/chat/{userChatId}")
    public ResponseEntity<String> chat(@PathVariable String userChatId,@RequestBody JsonNode jsonBody) {
        String chat = agent.chat(userChatId,jsonBody.toString());
        return new ResponseEntity<>(chat, HttpStatus.OK);
    }

    @GetMapping("/{gameId}/live/status")
    public ResponseEntity<MLBGameService.LiveGameStatus> getLiveGameStatus(@PathVariable String gameId) {
        return ResponseEntity.ok(mlbGameService.getLiveGameStatus(gameId));
    }

    private final GumboDataService gumboDataService;

    @GetMapping(path = "/live-feed", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<MLBGameEvent>> getLiveFeed() {
        return dataService.getLiveFeedStream()
                .map(event -> ServerSentEvent.<MLBGameEvent>builder()
                        .data(event)
                        .event("mlb-update")
                        .build());
    }

    @GetMapping("/all")
    public ResponseEntity<MLBResponse> getGames(){
        return new ResponseEntity<>(dataService.getSchedule("2024-03-02","2024-03-02"), HttpStatus.OK);
    }
}
