# ADR-0004: Cross-platform mobile and TypeScript stack

Status: Proposed

## Context

A cross-platform mobile frontend is mandatory; language and backend framework are free choices. Team familiarity is not specified.

## Options

React Native or Flutter for mobile; TypeScript/NestJS, Java/Spring or other backend frameworks.

## Decision and rationale

Propose React Native with Expo, NestJS services and React admin. A common language simplifies team collaboration and contract tooling. PostgreSQL provides relational transactions; RabbitMQ supports background delivery. Review camera, upload and identity integration on real target devices before accepting this decision.

## Consequences

Shared language does not eliminate mobile platform differences or production configuration. Expo build and native module requirements must be validated. Team familiarity may make Flutter or another backend a better choice.

## Validation

Prototype third-party sign-in, video capture/upload and playback on Android and iOS. Revise the stack before implementation if skills or native integration requirements favor alternatives.
