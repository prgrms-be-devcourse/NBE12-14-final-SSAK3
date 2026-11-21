package com.react.room.dto;

import java.util.List;

public record HostRoomHomeResponse(List<LiveRoomResponse> liveRooms, ReadyRoomPage readyRooms) {
}
