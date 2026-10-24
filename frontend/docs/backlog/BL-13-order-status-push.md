---
id: BL-13
title: Push order status changes to the browser
status: Open
importance: Later
services: [order-service, api-gateway]
blocks: —
created: 2026-10-20
---

# BL-13: Push order status changes to the browser

## Problem

Many order changes happen in the background: TTN generation (`PAID → PENDING_SHIPPING`), the cancellation saga (`CANCELLATION_REQUESTED → CANCELLED`), expiry (`EXPIRED`), delivery (`SHIPPED → DELIVERED`, 30 s after hand-over with the mock carrier). The browser only learns about them by asking again.

## Current behaviour

Only request/response endpoints; status changes are published as Kafka events (`order.order-events`, `delivery.shipment-events`), which browsers cannot read.

## Proposed change

Postponed; agreed 2026-10-20 that short polling is enough for the demo. If picked up: Server-Sent Events (simplest for one-way updates; needs streaming support through the gateway) or long polling on order details.

## Requirements

- [ ] Choose the mechanism and check it passes the gateway and Ingress

## Frontend impact

- Until done: order details, order history and the fulfillment queue poll every few seconds while an order is in a non-final state (iterations 3 and 5).
- After: polling is replaced by the push channel.
