package com.example.qrorder.ordering.dto;

import com.example.qrorder.ordering.model.OrderLine;
import java.time.Instant;
import java.util.List;

/**
 * One order that is ready to be carried out. waiterUserId/waiterName are null when nobody is responsible
 * for the table (unassigned is then true).
 */
public record WaiterTaskResponse(String orderId, String tableId, String tableNumber, String customerLabel,
                                 List<OrderLine> lines, Instant readyAt, long waitingSeconds,
                                 String waiterUserId, String waiterName, boolean mine, boolean unassigned,
                                 String pickedUpByUserId, String pickedUpByName, Instant pickedUpAt,
                                 boolean carriedByMe) {
}