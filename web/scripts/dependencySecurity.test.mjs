import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { test } from 'node:test';

const require = createRequire(import.meta.url);

// Exercise both shipped bundles: patching only index.js does not protect Rollup's entry point.
for (const entry of ['deepmerge', 'deepmerge/dist/umd.js']) {
  const merge = require(entry);
  for (const clone of [true, false]) {
    test(`${entry}: poisoned target keys cannot change the result prototype (clone=${clone})`, () => {
      const target = JSON.parse('{"__proto__":{"polluted":true},"nested":{"__proto__":{"polluted":true},"safe":1}}');
      const result = merge(target, { added: 2 }, { clone });
      assert.equal(Object.getPrototypeOf(result), Object.prototype);
      assert.equal(result.polluted, undefined);
      assert.equal(Object.hasOwn(result, '__proto__'), false);
      assert.equal(result.added, 2);
      if (clone) {
        assert.equal(Object.getPrototypeOf(result.nested), Object.prototype);
        assert.equal(result.nested.polluted, undefined);
      }
      assert.equal({}.polluted, undefined);
    });

    test(`${entry}: null-prototype targets cannot bypass source key protection (clone=${clone})`, () => {
      const result = merge(Object.create(null), JSON.parse('{"__proto__":{"polluted":true},"safe":1}'), { clone });
      assert.equal(Object.getPrototypeOf(result), Object.prototype);
      assert.equal(result.polluted, undefined);
      assert.equal(Object.hasOwn(result, '__proto__'), false);
      assert.equal(result.safe, 1);
    });
  }

  test(`${entry}: retains normal deep-merge behavior used by Rollup`, () => {
    const symbol = Symbol('option');
    const target = { nested: { left: 1 }, extensions: ['.js'], [symbol]: { left: 1 } };
    const source = { nested: { right: 2 }, extensions: ['.ts'], [symbol]: { right: 2 } };
    const expected = { nested: { left: 1, right: 2 }, extensions: ['.js', '.ts'], [symbol]: { left: 1, right: 2 } };
    assert.deepEqual(merge(target, source), expected);
    assert.deepEqual(merge.all([target, source]), expected);
    assert.deepEqual(target.nested, { left: 1 });
  });
}
