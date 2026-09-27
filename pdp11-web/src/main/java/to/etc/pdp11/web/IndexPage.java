package to.etc.pdp11.web;

import to.etc.domui.component.layout.CaptionedHeader;
import to.etc.domui.component.layout.ContentPanel;
import to.etc.domui.component.layout.title.AppPageTitleBar;
import to.etc.domui.component.misc.ALink;
import to.etc.domui.component.misc.Explanation;
import to.etc.domui.dom.html.UrlPage;
import to.etc.domui.state.PageParameters;
import to.etc.pdp11.common.microcode.MicrocodeSource;

/**
 * What the site has: for now, the microcode of three processors.
 */
public final class IndexPage extends UrlPage {
	@Override
	public void createContent() throws Exception {
		setPageTitle("PDP-11 tools");
		add(new AppPageTitleBar("PDP-11 tools", false));
		ContentPanel cp = new ContentPanel();
		add(cp);

		cp.add(new CaptionedHeader("Microcode"));
		cp.add(new Explanation("A processor's microcode, one microword at a time: what its bits are set to, "
			+ "what that means, and where in DEC's documentation it was read from."));
		for(MicrocodeSource source : MicrocodeSource.values()) {
			PageParameters pp = new PageParameters();
			pp.addParameter(MicrocodePage.PARAM_SOURCE, source.name());
			//-- A real link rather than a button, so it can be opened in a tab or bookmarked.
			ALink link = new ALink(MicrocodePage.class, pp);
			link.setText(source.getLabel());
			link.addCssClass("pdp-tool-link");
			cp.add(link);
		}
	}
}
