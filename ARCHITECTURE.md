# Project architecture

Architecture of the implementation at `b91d6ec`, including the live scan map. The integrated navigation flow runs on Android; the shared navigation core is pure Kotlin.

```mermaid
flowchart TB
    subgraph device[Android device]
        subgraph sensors[Shared camera session]
            AR["ARCore session<br/>Owned by indoor-perception / ArFrameSource"]
        end

        subgraph native[Native Android modules]
            CAP["IndoorPerception.captureFrame<br/>RGB to JPEG"]
            DET["IndoorPerception.analyzeFrame<br/>YOLO ONNX + ML Kit OCR"]
            RT["NavigationSensorBridge / NavigationRuntime<br/>Pose + depth conversion<br/>Single engine thread; latest-frame queue"]
            subgraph core["navigation-core · pure Kotlin"]
                MAP["Floor estimation + occupancy grid<br/>Obstacle inflation"]
                PLAN["Frontier exploration + semantic hints<br/>Topological graph + A* planning"]
                CTRL["Navigation controller + state machine<br/>Initial scan and tracking / depth gates"]
                MAP --> PLAN --> CTRL
            end
            PNG["OccupancyMapRenderer<br/>Grid + path + frontiers + user pose"]
        end

        subgraph js[Expo / React Native]
            UI["Navigate tab<br/>Session controls, targets, status, scan map"]
            HYB["Hybrid perception<br/>Native inference + optional VLM<br/>Validation + PerceptionFrame cache"]
            SEM["perceptionToSemantic<br/>Landmarks and sign observations"]
            ADAPT["navigationCommandSource<br/>Snapshot to guidance command"]
            GUIDE["Guidance NavigationController<br/>Speech + haptic patterns"]
            HOME["Home screen<br/>Microphone recording"]
            PARSE["commandFromTranscript<br/>Left / right / straight / stop / arrived"]
        end

        USER["User<br/>Spoken instructions, vibration, visual map"]
    end

    GEM["Gemini API<br/>Optional visual interpretation<br/>Voice transcription"]

    AR -->|RGB| CAP
    AR -->|pose and depth frames| RT
    RT -->|NavigationFrame| MAP
    UI -->|start / stop / reset / target| RT
    RT -->|semantic evidence| PLAN
    UI -->|capture every 2.5 s when idle| CAP
    CAP -->|JPEG base64| HYB
    HYB -->|analyzeFrame| DET
    DET -->|objects and OCR text| HYB
    HYB -.->|gated image request| GEM
    GEM -.->|visual JSON| HYB
    HYB -->|validated PerceptionFrame| SEM
    SEM -->|SemanticObservation array| RT
    SEM -->|immediate hazard STOP via Navigate| GUIDE
    CTRL -->|compact NavigationSnapshot via runtime| ADAPT
    ADAPT -->|command via Navigate; repeats filtered| GUIDE
    ADAPT -->|status and debug counters| UI
    MAP -->|native grid access| PNG
    PLAN -->|path and frontiers| PNG
    RT -->|latest pose| PNG
    UI -->|getMapImage every 700 ms| PNG
    PNG -->|PNG base64; debug enabled only| UI
    HOME -->|recorded audio| GEM
    GEM -->|transcript| PARSE
    PARSE -->|guidance command| GUIDE
    GUIDE -->|expo-speech and haptics| USER
    UI -->|live occupancy map and scan progress| USER

    classDef sensor fill:#e0f2fe,stroke:#0284c7,color:#0c4a6e
    classDef navigation fill:#dcfce7,stroke:#16a34a,color:#14532d
    classDef frontend fill:#ede9fe,stroke:#7c3aed,color:#4c1d95
    classDef cloud fill:#fff7ed,stroke:#ea580c,color:#7c2d12
    class AR,CAP,DET sensor
    class RT,MAP,PLAN,CTRL,PNG navigation
    class UI,HYB,SEM,ADAPT,GUIDE,HOME,PARSE frontend
    class GEM cloud
```

## Boundaries and behavior

- **One ARCore session:** `indoor-perception` owns the camera in the Navigate flow and forwards native frames through `NavigationSensorBridge`. The engine-debug screen has an alternative navigation-owned session for isolated testing.
- **Mapping stays native:** depth clouds and occupancy grids do not cross into JavaScript. Navigation emits small snapshots, normally throttled to roughly eight per second, with immediate updates on command or status changes.
- **Perception is slower:** Navigate attempts capture every 2.5 seconds and skips overlapping work. Native YOLO and OCR run before optional Gemini interpretation; visual results become semantic hints and hazard alerts.
- **Navigation gates movement:** the default initial scan is 10 seconds of qualifying scan time. Tracking, depth, map confidence, and route checks can keep movement blocked longer.
- **The map is a preview:** a native renderer reads the engine's grid, path, frontiers, goal, and pose on the engine thread. Navigate requests its PNG every 700 ms while running, with overlapping requests skipped. Debug mode is enabled by this screen and required by the renderer API.
- **Voice is a separate flow:** Home records audio, asks Gemini for a transcript, and maps recognized command words directly to speech and haptics. It does not currently set a navigation destination.
- **Cloud boundary:** this flow calls Gemini directly from the app. Mapping and planning have no Gemini dependency; visual interpretation is gated and optional, while Home's transcription uses Gemini.

## Source map

| Responsibility | Entry point |
| --- | --- |
| Navigate orchestration and scan-map display | [`navigate.tsx`](my-app/src/app/navigate.tsx) |
| ARCore camera ownership and frame sharing | [`ArFrameSource.kt`](my-app/modules/indoor-perception/android/src/main/java/expo/modules/indoorperception/ArFrameSource.kt) |
| Hybrid perception and VLM gate | [`hybridPerception.js`](my-app/src/services/hybridPerception.js) |
| Perception-to-navigation conversion | [`perceptionToSemantic.ts`](my-app/src/perception/perceptionToSemantic.ts) |
| Threading, frame conversion, and native bridge runtime | [`NavigationRuntime.kt`](my-app/modules/navigation-native/android/src/main/java/expo/modules/navigationnative/NavigationRuntime.kt) |
| Mapping, exploration, planning, and control | [`NavigationEngine.kt`](my-app/navigation-core/src/main/kotlin/com/navassist/navcore/NavigationEngine.kt) |
| Native map image | [`OccupancyMapRenderer.kt`](my-app/modules/navigation-native/android/src/main/java/expo/modules/navigationnative/platform/OccupancyMapRenderer.kt) |
| Snapshot-to-guidance adapter | [`navigationCommandSource.ts`](my-app/src/guidance/navigationCommandSource.ts) |
| Speech and haptic output | [`NavigationController.ts`](my-app/src/guidance/NavigationController.ts) |
| Home voice transcription | [`GeminiSpeechService.ts`](my-app/src/ai/GeminiSpeechService.ts) |

This documents source-code wiring, not a new verification of behavior on a physical phone.
