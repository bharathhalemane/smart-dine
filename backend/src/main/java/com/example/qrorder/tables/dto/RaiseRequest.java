package com.example.qrorder.tables.dto;

import com.example.qrorder.tables.model.RequestKind;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** toUserId only applies to a REASSIGN request: the colleague the waiter suggests. */
public record RaiseRequest(@NotNull RequestKind kind, @Size(max = 200) String note, @Size(max = 64) String toUserId) {
}