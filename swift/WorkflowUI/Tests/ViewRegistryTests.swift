/*
 * Copyright 2019 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import XCTest

@testable import WorkflowUI


fileprivate struct ScreenA: Screen {}
fileprivate struct ScreenB: Screen {}

fileprivate class ViewA: ScreenViewController<ScreenA> {}
fileprivate class ViewB: ScreenViewController<ScreenB> {}
fileprivate class ViewC: ScreenViewController<ScreenA> {}

class ViewRegistryTests: XCTestCase {

    func test_can_provides_for_registered_views() {
        var viewRegistry = ViewRegistry()
        viewRegistry.register(screenViewControllerType: ViewA.self)

        XCTAssertFalse(viewRegistry.canProvideView(for: ScreenB.self))
        XCTAssertTrue(viewRegistry.canProvideView(for: ScreenA.self))
    }

    func test_provides_for_registered_views() {
        var viewRegistry = ViewRegistry()
        viewRegistry.register(screenViewControllerType: ViewA.self)

        XCTAssertTrue(viewRegistry.canProvideView(for: ScreenA.self))
        let actual = viewRegistry.provideView(for: ScreenA())

        XCTAssertTrue(type(of: actual) == ViewA.self)
    }

    func test_merge_joins_registries() {
        var viewRegistry1 = ViewRegistry()
        var viewRegistry2 = ViewRegistry()

        viewRegistry1.register(screenViewControllerType: ViewA.self)
        viewRegistry2.register(screenViewControllerType: ViewB.self)

        viewRegistry1.merge(with: viewRegistry2)
        XCTAssertTrue(viewRegistry1.canProvideView(for: ScreenA.self))
        XCTAssertTrue(viewRegistry1.canProvideView(for: ScreenB.self))
    }

    func test_merge_other_registry_wins_for_duplicates() {
        var viewRegistry1 = ViewRegistry()
        var viewRegistry2 = ViewRegistry()

        viewRegistry1.register(screenViewControllerType: ViewA.self)


        viewRegistry2.register(screenViewControllerType: ViewC.self)


        viewRegistry1.merge(with: viewRegistry2)
        XCTAssertTrue(viewRegistry1.canProvideView(for: ScreenA.self))
        let actual = viewRegistry1.provideView(for: ScreenA())
        XCTAssertTrue(type(of: actual) == ViewC.self)
    }
}
