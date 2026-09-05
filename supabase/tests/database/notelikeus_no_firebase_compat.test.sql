-- Firebase UID mapping objects must not exist after 20260905000000.

begin;
select plan(6);

select hasnt_table(
  'public',
  'firebase_uid_mappings',
  'firebase_uid_mappings table is removed'
);

select hasnt_function(
  'public',
  'link_firebase_uid',
  'link_firebase_uid is removed'
);

select hasnt_function(
  'public',
  'get_linked_firebase_uid',
  'get_linked_firebase_uid is removed'
);

select hasnt_function(
  'public',
  'link_verified_firebase_uid',
  'link_verified_firebase_uid is removed'
);

select hasnt_function(
  'public',
  'firebase_uid_proven_elsewhere',
  'firebase_uid_proven_elsewhere is removed'
);

select hasnt_function(
  'public',
  'get_firebase_uid_link',
  'get_firebase_uid_link is removed'
);

select * from finish();
rollback;
