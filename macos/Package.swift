// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "SwiftShareDomain",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "SwiftShareDomain", targets: ["SwiftShareDomain"])
    ],
    targets: [
        .target(
            name: "SwiftShareDomain",
            path: "SwiftShareDomain"
        ),
        .testTarget(
            name: "SwiftShareDomainTests",
            dependencies: ["SwiftShareDomain"],
            path: "SwiftShareDomainTests"
        )
    ]
)
