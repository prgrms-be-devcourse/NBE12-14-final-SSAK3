package com.react.room.controller;

import com.react.global.response.ApiResponse;
import com.react.room.dto.CreateRoomRequest;
import com.react.room.dto.CreateRoomResponse;
import com.react.room.dto.EndRoomResponse;
import com.react.room.dto.HostRoomHomeResponse;
import com.react.room.dto.LiveRoomResponse;
import com.react.room.dto.ReadyRoomPage;
import com.react.room.dto.RoomDetailResponse;
import com.react.room.dto.RoomInvitationResponse;
import com.react.room.dto.StartRoomResponse;
import com.react.room.service.LectureRoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
public class LectureRoomController {

    private final LectureRoomService lectureRoomService;

    @PostMapping
    public ResponseEntity<ApiResponse<CreateRoomResponse>> create(
            @Valid @RequestBody CreateRoomRequest request,
            Authentication authentication
    ) {
        CreateRoomResponse response =
                lectureRoomService.create(request, authentication);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.of(response));
    }

    @GetMapping("/live")
    public ApiResponse<List<LiveRoomResponse>> liveRooms(Authentication authentication) {
        return ApiResponse.of(lectureRoomService.liveRooms(authentication));
    }

    @GetMapping("/home")
    public ApiResponse<HostRoomHomeResponse> home(Authentication authentication,
                                                  @RequestParam(defaultValue = "3") int readyLimit) {
        return ApiResponse.of(lectureRoomService.home(authentication, readyLimit));
    }

    @GetMapping("/ready")
    public ApiResponse<ReadyRoomPage> readyRooms(Authentication authentication,
                                                 @RequestParam(required = false) String cursor) {
        return ApiResponse.of(lectureRoomService.readyRooms(authentication, cursor));
    }

    @GetMapping("/{roomId}/invitation")
    public ApiResponse<RoomInvitationResponse> invitation(
            @PathVariable Long roomId,
            Authentication authentication
    ) {
        RoomInvitationResponse response =
                lectureRoomService.invitation(roomId, authentication);

        return ApiResponse.of(response);
    }

    @GetMapping("/{roomId}")
    public ApiResponse<RoomDetailResponse> detail(
            @PathVariable Long roomId,
            Authentication authentication
    ) {
        RoomDetailResponse response =
                lectureRoomService.detail(roomId, authentication);

        return ApiResponse.of(response);
    }

    @DeleteMapping("/{roomId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long roomId, Authentication authentication) {
        lectureRoomService.deleteReadyRoom(roomId, authentication);
    }

    @PostMapping("/{roomId}/start")
    public ApiResponse<StartRoomResponse> start(
            @PathVariable Long roomId,
            Authentication authentication
    ) {
        return ApiResponse.of(
                lectureRoomService.start(roomId, authentication)
        );
    }

    @PostMapping("/{roomId}/end")
    public ApiResponse<EndRoomResponse> end(
            @PathVariable Long roomId,
            Authentication authentication
    ) {
        return ApiResponse.of(
                lectureRoomService.end(roomId, authentication)
        );
    }
}
