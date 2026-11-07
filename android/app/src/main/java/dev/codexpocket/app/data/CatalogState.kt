package dev.codexpocket.app.data

enum class ModelCatalogState { Idle, Loading, Ready, Empty, Unsupported, Offline, Failed }
enum class PairingPhase { Idle, Validating, Connecting, Authenticating, Synchronizing, Paired, Failed }
