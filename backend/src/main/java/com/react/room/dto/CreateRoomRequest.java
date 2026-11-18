package com.react.room.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoomRequest(

        @NotBlank
        @Size(max = 200)
        String title,

        @Size(max = 10000)
        String description

) {
}