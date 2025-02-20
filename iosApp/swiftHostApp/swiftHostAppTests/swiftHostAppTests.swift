//
//  swiftHostAppTests.swift
//  swiftHostAppTests
//
//  Created by Pavel.Punegov on 05.12.2024.
//

import XCTest
@testable import swiftHostApp
import KotlinTests

final class swiftHostAppTests: XCTestCase {
    
    override class var defaultTestSuite: XCTestSuite {
        let defaultSuite = super.defaultTestSuite

        // Add all Kotlin tests to the default test suite
        ConfigurationKt.testSuite().tests.forEach { test in
            defaultSuite.addTest(test)
        }

        return defaultSuite
    }
}
