import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { AppService, Configuration } from 'src/app/app.service';

@Injectable({
  providedIn: 'root'
})
export class ConfigurationsService {

  constructor(
    private http: HttpClient,
    private appService: AppService,
  ) { }

  getConfiguration(selectedConfiguration: string, loadedConfiguration: boolean){
    let uri = "configurations";

    if (selectedConfiguration != "All") uri += "/" + selectedConfiguration;
    if (loadedConfiguration) uri += "?loadedConfiguration=true";

    return this.http.get(this.appService.absoluteApiPath + uri, { responseType: 'text' });
  }

  getConfigurations(){
    return this.http.get<Configuration[]>(this.appService.absoluteApiPath + "server/configurations");
  }

  getConfigurationVersions(configurationName: string){
    return this.http.get<Configuration[]>(this.appService.absoluteApiPath + "configurations/" + configurationName + "/versions");
  }

  postConfiguration(data: FormData){
    return this.http.post<Record<string, string>>(this.appService.absoluteApiPath + "configurations", data);
  }

  updateConfigurationVersion(configurationName: string, configurationVersion: string, configuration: Record<string, any>){
    return this.http.put(this.appService.absoluteApiPath + "configurations/" + configurationName + "/versions/" + configurationVersion, configuration);
  }

  deleteConfigurationVersion(configurationName: string, configurationVersion: string){
    return this.http.delete(this.appService.absoluteApiPath + "configurations/" + configurationName + "/versions/" + configurationVersion);
  }

}
