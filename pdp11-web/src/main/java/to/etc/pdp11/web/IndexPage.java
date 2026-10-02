package to.etc.pdp11.web;

import to.etc.domui.component.layout.ContentPanel;
import to.etc.domui.component.misc.ALink;
import to.etc.domui.dom.html.HTag;
import to.etc.domui.dom.html.Para;
import to.etc.domui.dom.html.UrlPage;
import to.etc.domui.state.PageParameters;
import to.etc.pdp11.common.microcode.MicrocodeSource;

/**
 * What the site has: for now, the microcode of three processors.
 */
public final class IndexPage extends UrlPage {
	@Override
	public void createContent() throws Exception {
		setPageTitle("Tools");
		ContentPanel cp = new ContentPanel();
		add(cp);

		cp.add(new HTag(2, "PDP 11 Microcode"));
		Para intro = new Para();
		intro.setText("Interactive microcode listings");
		cp.add(intro);
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

	@Override public String getPageTitle() {
		return "etc.to tools";
	}
}
