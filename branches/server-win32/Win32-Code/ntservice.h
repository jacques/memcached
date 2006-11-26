/* ntservice.h
 *
 *  Copyright (c) 2006 Germán Méndez Bravo (Kronuz) <kronuz@users.sf.net>
 *  All rights reserved.
 *
 */

#ifndef SERVICE_H
#define SERVICE_H

int ServiceStart();
int ServiceStop();
int ServiceRestart();
int ServiceUninstall();
int ServiceInstall();
int ServiceRun();

#endif
