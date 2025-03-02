import { Directive, ElementRef, Input, OnChanges, OnInit, SimpleChanges } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import * as Prism from 'prismjs';

@Directive({
  selector: '[appFormatCode]'
})
export class FormatCodeDirective implements OnInit, OnChanges {
  @Input() text: string = "";

  private element = this.elementRef.nativeElement;
  private code = document.createElement('code');
  private initHash = "";

  constructor(
    private router: Router,
    private route: ActivatedRoute,
    private elementRef: ElementRef<HTMLElement>,
  ) { };

  ngOnInit(){
    this.element.classList.add("line-numbers");
    this.element.classList.add("language-markup");
    this.element.append(this.code);
    this.route.fragment.subscribe(hash => this.initHash = hash ?? "");
  }

  ngOnChanges(changes: SimpleChanges) {
    if (this.text && this.text != ''/*  && this.initHash !== '' */) {
      $(this.code).text(this.text);
      Prism.highlightElement(this.code);

      this.addOnClickEvent(this.code);

      // $location.hash(this.initHash);
      if(this.initHash != ""){
        let el = $("#" + this.initHash);
        if (el) {
          el.addClass("line-selected");
          let lineNumber = Math.max(0, parseInt(this.initHash.substring(1)) - 15);
          setTimeout(() => {
            let lineElement = $("#L" + lineNumber)[0];
            if (lineElement) {
              lineElement.scrollIntoView();
            }
          }, 500)
        }
      }
    } else if (this.text === '') {
      $(this.code).text(this.text);
    }
  }

  addOnClickEvent(root: HTMLElement) {
    let spanElements = $(root).children("span.line-numbers-rows").children("span");
    spanElements.on("click", (event) => {
      let target = $(event.target);
      target.parent().children(".line-selected").removeClass("line-selected");
      let anchor = target.attr('id');
      target.addClass("line-selected");
      this.router.navigate([], { relativeTo: this.route, queryParams: this.route.snapshot.queryParams, fragment: anchor });
    });
  }
}
