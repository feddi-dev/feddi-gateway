# Fusion planner fixtures

Imported from [ChilliCream/graphql-platform](https://github.com/ChilliCream/graphql-platform) 972f3f9 (`972f3f9e39f19614d625cd3948a52c5d89565d03`), `src/HotChocolate/Fusion/test/Fusion.Execution.Tests/Planning`, with `tools/planner-compare/import-fusion-tests.mjs`. Do not edit by hand; re-run the import.

Each directory holds one test: `schema.yaml` (source schemas a, b, ...), `query.graphql` and Fusion's plan
snapshot `fusion-plan.yaml`. `FusionPlanParityTest` plans every query with feddi and compares it with
Fusion's plan.

91 tests imported, 163 skipped (see SKIPPED.md).

## License

The fixtures are derived from Fusion's tests, which are licensed under the MIT License:

```
MIT License

Copyright (c) 2018 - present ChilliCream Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
