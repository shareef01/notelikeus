import { describe, expect, it } from 'vitest';
import { unexplainedMissingCloudIds } from '@/lib/notes/unexplainedMissingCloudIds';

const none = () => false;

describe('unexplainedMissingCloudIds', () => {
  it('returns nothing when every known id is still in the snapshot', () => {
    expect(unexplainedMissingCloudIds(['a', 'b'], new Set(['a', 'b', 'c']), none)).toEqual([]);
  });

  it('reports ids that vanished with no tombstone to explain them', () => {
    expect(unexplainedMissingCloudIds(['a', 'b'], new Set(), none)).toEqual(['a', 'b']);
  });

  it('treats a tombstone as the explanation', () => {
    const tombstoned = new Set(['a']);
    expect(
      unexplainedMissingCloudIds(['a', 'b'], new Set(), (id) => tombstoned.has(id)),
    ).toEqual(['b']);
  });

  it('returns nothing when every missing id is tombstoned — a legitimately emptied cloud', () => {
    expect(unexplainedMissingCloudIds(['a', 'b'], new Set(), () => true)).toEqual([]);
  });

  it('accepts a Set of known ids and does not report an id twice', () => {
    expect(unexplainedMissingCloudIds(new Set(['a', 'a', 'b']), new Set(), none)).toEqual([
      'a',
      'b',
    ]);
    expect(unexplainedMissingCloudIds(['a', 'a'], new Set(), none)).toEqual(['a']);
  });
});
