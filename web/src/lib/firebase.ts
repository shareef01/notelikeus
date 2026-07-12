import { initializeApp, type FirebaseApp } from 'firebase/app';

import { getAuth, type Auth } from 'firebase/auth';

import {

  initializeFirestore,

  memoryLocalCache,

  persistentLocalCache,

  persistentMultipleTabManager,

  type Firestore,

} from 'firebase/firestore';

import { loadFirebaseEnv } from './config';


let app: FirebaseApp | null = null;

let auth: Auth | null = null;

let db: Firestore | null = null;

let initError: Error | null = null;



function createFirestore(instance: FirebaseApp): Firestore {

  const canUseIndexedDb = typeof indexedDB !== 'undefined';



  if (!canUseIndexedDb) {

    return initializeFirestore(instance, { localCache: memoryLocalCache(), experimentalForceLongPolling: true });

  }



  try {

    return initializeFirestore(instance, {

      localCache: persistentLocalCache({

        tabManager: persistentMultipleTabManager(),

      }),

      experimentalForceLongPolling: true,

    });

  } catch (error) {

    console.warn('[Firebase] Persistent cache unavailable, using memory cache.', error);

    return initializeFirestore(instance, { localCache: memoryLocalCache(), experimentalForceLongPolling: true });

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

} {

  if (app && auth && db) {

    return { app, auth, db };

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



    return { app, auth, db };

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




