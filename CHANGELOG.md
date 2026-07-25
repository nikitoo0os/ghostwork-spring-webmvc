# Changelog

## 0.8.0

- Aligned dependencies and compatibility documentation with GhostWork 0.8.
- Preserved the 0.7 Servlet request and cancellation lifecycle contracts.

## 0.7.0

- Connected Servlet timeout and client-abort lifecycle signals to cancellation
  policies while preserving `TIMED_OUT` and `ABORTED` operation states.
- Added deterministic duplicate-callback protection tests.
- Ordered Web MVC auto-configuration after the core Spring integration.

## 0.6.0

- Added automatic synchronous and Servlet async request ownership.
