package com.react.room.controller;

import com.react.global.response.ApiResponse;
import com.react.room.dto.PublicRoomResponse;
import com.react.room.service.LectureRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public/rooms")
@RequiredArgsConstructor
public class PublicLectureRoomController {

    private final LectureRoomService lectureRoomService;

    @GetMapping("/{joinCode}")
    public ApiResponse<PublicRoomResponse> detail(
            @PathVariable String joinCode
    ) {
        PublicRoomResponse response =
                lectureRoomService.publicDetail(joinCode);

        return ApiResponse.of(response);
    }
}