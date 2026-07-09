import { initializeApp, type FirebaseApp } from 'firebase/app';

import { getAuth, type Auth } from 'firebase/auth';

import {

  initializeFirestore,

  memoryLocalCache,

  persistentLocalCache,

  persistentMultipleTabManager,

  type Firestore,

} from 'firebase/firestore';

import { getStorage, type FirebaseStorage } from 'firebase/storage';

import { loadFirebaseEnv } from './config';



let app: FirebaseApp | null = null;

let auth: Auth | null = null;

let db: Firestore | null = null;

let storage: FirebaseStorage | null = null;

let initError: Error | null = null;



function createFirestore(instance: FirebaseApp): Firestore {

  const canUseIndexedDb = typeof indexedDB !== 'undefined';



  if (!canUseIndexedDb) {

    return initializeFirestore(instance, { localCache: memoryLocalCache() });

  }



  try {

    return initializeFirestore(instance, {

      localCache: persistentLocalCache({

        tabManager: persistentMultipleTabManager(),

      }),

    });

  } catch (error) {

    console.warn('[Firebase] Persistent cache unavailable, using memory cache.', error);

    return initializeFirestore(instance, { localCache: memoryLocalCache() });

  }

}



/**

 * Initializes Firebase once with Firestore offline persistence when available.

 * Falls back to memory cache if IndexedDB is blocked (private browsing, strict shields).

 */

export function initFirebase(): {

  app: FirebaseApp;

  auth: Auth;

  db: Firestore;

  storage: FirebaseStorage;

} {

  if (app && auth && db && storage) {

    return { app, auth, db, storage };

  }



  if (initError) {

    throw initError;

  }



  try {

    const env = loadFirebaseEnv();

    app = initializeApp({

      apiKey: env.apiKey,

      authDomain: env.authDomain,

      projectId: env.projectId,

      storageBucket: env.storageBucket,

      messagingSenderId: env.messagingSenderId,

      appId: env.appId,

    });



    auth = getAuth(app);

    db = createFirestore(app);

    storage = getStorage(app);



    return { app, auth, db, storage };

  } catch (error) {

    initError = error instanceof Error ? error : new Error(String(error));

    throw initError;

  }

}



export function getFirebaseAuth(): Auth {

  return initFirebase().auth;

}



export function getFirestoreDb(): Firestore {

  return initFirebase().db;

}



export function getFirebaseStorage(): FirebaseStorage {

  return initFirebase().storage;

}


