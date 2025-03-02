import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'dropLastChar'
})
export class DropLastCharPipe implements PipeTransform {

  transform(input: string): string {
    if (input && input.length > 0) {
      return input.substring(0, input.length - 1);
    }
    return input;
  }

}
