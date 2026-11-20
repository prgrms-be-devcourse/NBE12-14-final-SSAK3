package com.react.room.controller;

import com.react.global.response.ApiResponse;
import com.react.room.dto.CreateRoomRequest;
import com.react.room.dto.CreateRoomResponse;
import com.react.room.dto.EndRoomResponse;
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
