package to.etc.pdp11.web;

import to.etc.domui.component.buttons.LinkButton;
import to.etc.domui.dom.html.Div;
import to.etc.domui.dom.html.HTag;
import to.etc.domui.dom.html.UrlPage;
import to.etc.domui.state.UIGoto;
import to.etc.pdp11.common.microcode.MicrocodeSource;

/**
 * What the site has: for now, the microcode of three processors.
 */
public final class IndexPage extends UrlPage {
	@Override
	public void createContent() throws Exception {
		setPageTitle("PDP-11 tools");
		addCssClass("pdp-page");
		add(new HTag(1, "PDP-11 tools"));

		add(new HTag(2, "Microcode"));
		Div intro = new Div("pdp-intro");
		intro.setText("A processor's microcode, one microword at a time: what its bits are set to, "
			+ "what that means, and where in DEC's documentation it was read from.");
		add(intro);
		Div list = new Div("pdp-tools");
		add(list);
		for(MicrocodeSource source : MicrocodeSource.values()) {
			Div row = new Div();
			list.add(row);
			row.add(new LinkButton(source.getLabel(),
				b -> UIGoto.moveSub(MicrocodePage.class, MicrocodePage.PARAM_SOURCE, source.name())));
		}
	}
}
