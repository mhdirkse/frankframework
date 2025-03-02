import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { AppService, Certificate } from 'src/app/app.service';

export interface CertificateList {
  adapter: string;
  pipe: string;
  certificate: Certificate;
}

export interface SecurityRole {
  allowed: boolean;
  name: string;
}

export interface AuthEntry {
  alias: string;
  username: string;
  password: string;
}

export interface SapSystem {
  name: string;
  info: string;
}

export interface JmsRealm {
  name: string;
  datasourceName: string;
  queueConnectionFactoryName: string;
  topicConnectionFactoryName: string;
  info: string;
  connectionPoolProperties: string;
}

export interface ServerProps {
  maximumTransactionTimeout: string;
  totalTransactionLifetimeTimeout: string;
}

export interface Datasource {
  datasourceName: string;
  info: string;
  connectionPoolProperties: string;
}

interface SecurityItems {
  securityRoles: SecurityRole[];
  datasources: Datasource[];
  authEntries: AuthEntry[];
  jmsRealms: JmsRealm[];
  sapSystems: SapSystem[];
  xmlComponents: Record<string, string>;
}

@Injectable({
  providedIn: 'root'
})
export class SecurityItemsService {

  constructor(
    private http: HttpClient,
    private appService: AppService,
  ) { }

  getSecurityItems(){
    return this.http.get<SecurityItems>(this.appService.absoluteApiPath + "securityitems");
  }
}
